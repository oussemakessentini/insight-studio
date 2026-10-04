package com.oussamaksantini.insightstudio.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.ApiInstance;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Payment-provider calls run outside database transactions (docs/billing-api.md, "Provider calls"): the
 * real Stripe adapter of a separate API instance talks to a local stub of Stripe's API (no network) that
 * can be slow, time out, fail or block, and that honours idempotency keys the way Stripe documents (same
 * key, same object; a request with a key still in flight is refused with 409 and not recorded).
 */
class BillingProviderCallsIntegrationTest extends PostgresIntegrationTest {

    private static final String PRICE = "price_pro_test";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcConnectionDetails database;

    StripeStub stripe;
    ApiInstance api;
    long shop;
    final ExecutorService pool = Executors.newFixedThreadPool(6);

    @BeforeEach
    void setUp() throws IOException {
        new SqlFixture(jdbc).clear();
        shop = new SqlFixture(jdbc).business("Slow Co", "slow-co", "USD", "UTC");
        new TestAccounts(jdbc).member("owner@slow.test", shop, Role.OWNER);
        stripe = new StripeStub();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("insight.billing.provider", "stripe");
        properties.put("insight.billing.stripe.secret-key", "sk_test_stub");
        properties.put("insight.billing.stripe.webhook-secret", "whsec_stub");
        properties.put("insight.billing.stripe.api-base", "http://127.0.0.1:" + stripe.port());
        // Longer than anything a test does while a call is held (a business deletion on a busy machine took
        // several seconds); the timeout tests make the stub slower than this. Spring's read timeout covers
        // the whole response, body included, so a stalled provider never hangs a request.
        properties.put("insight.billing.stripe.read-timeout", "PT10S");
        properties.put("insight.billing.plans.pro.provider-price-id", PRICE);
        // The test runs the workers itself.
        properties.put("insight.billing.worker.poll-interval", "PT1H");
        api = ApiInstance.start(database, properties);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        api.close();
        stripe.close();
    }

    private HttpApiClient owner() throws Exception {
        HttpApiClient client = api.client();
        client.get("/api/session");
        HttpResponse<String> signedIn = client.postJson("/api/auth/sign-in",
                "{\"email\":\"owner@slow.test\",\"password\":\"%s\"}".formatted(TestAccounts.PASSWORD));
        assertThat(signedIn.statusCode()).as(signedIn.body()).isEqualTo(200);
        return client;
    }

    private HttpResponse<String> checkout(HttpApiClient client) throws Exception {
        return client.postJson("/api/businesses/%d/billing/checkout".formatted(shop), "{\"plan\":\"pro\"}");
    }

    /** No connection of the database is inside a transaction (other than this query's own). */
    private long openTransactions() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND pid <> pg_backend_pid() AND state LIKE 'idle in transaction%'
                """, Long.class);
    }

    /** Whether the business's billing row can be locked right now (nobody holds it). */
    private boolean billingRowFree() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("SET LOCAL lock_timeout = '200ms'");
                statement.executeQuery("SELECT 1 FROM business_subscriptions WHERE business_id = " + shop + " FOR UPDATE");
                statement.executeQuery("SELECT 1 FROM businesses WHERE id = " + shop + " FOR UPDATE");
                return true;
            } catch (java.sql.SQLException e) {
                return false;
            } finally {
                connection.rollback();
            }
        }
    }

    private List<Map<String, Object>> operations(String kind) {
        return jdbc.queryForList("SELECT * FROM billing_operations WHERE kind = ? ORDER BY id", kind);
    }

    @Test
    void aSlowProviderHoldsNoTransactionAndADeletionMeanwhileExpiresTheCheckout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        stripe.when("POST /v1/checkout/sessions", stub -> {
            stub.arrived.countDown();
            await(release);
            return null;   // then answer normally
        });
        HttpApiClient client = owner();
        Future<HttpResponse<String>> pending = pool.submit(() -> checkout(client));
        assertThat(stripe.arrived.await(10, TimeUnit.SECONDS)).isTrue();

        // While Stripe takes its time: no transaction is open and the business's rows are free.
        assertThat(openTransactions()).isZero();
        assertThat(billingRowFree()).isTrue();
        // So the owner can even delete the business meanwhile.
        HttpApiClient other = owner();
        HttpResponse<String> deleted = other.deleteJson("/api/businesses/" + shop,
                "{\"password\":\"%s\",\"confirmName\":\"Slow Co\"}".formatted(TestAccounts.PASSWORD));
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);

        release.countDown();
        HttpResponse<String> answered = pending.get(10, TimeUnit.SECONDS);
        assertThat(answered.statusCode()).as(answered.body()).isEqualTo(404);
        String session = stripe.created("cs_test_").getFirst();
        assertThat(operations("create_checkout")).singleElement()
                .satisfies(op -> assertThat(op).containsEntry("status", "ABANDONED").containsEntry("checkout_id", session));
        assertThat(operations("expire_checkout")).singleElement()
                .satisfies(op -> assertThat(op).containsEntry("status", "PENDING").containsEntry("checkout_id", session));

        // The worker expires the session at Stripe; it can never be paid.
        assertThat(api.bean(BillingCancellationWorker.class).processDue()).isEqualTo(1);
        assertThat(stripe.requests()).contains("POST /v1/checkout/sessions/" + session + "/expire");
        assertThat(stripe.sessionStatus(session)).isEqualTo("expired");
        assertThat(operations("expire_checkout").getFirst()).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void aTimedOutCallIsRetriedWithTheSameKeyAndCreatesNothingTwice() throws Exception {
        // The first checkout request reaches Stripe, which creates the session, but answers after the
        // API's 10 s read timeout: the outcome is unknown to the API.
        AtomicInteger calls = new AtomicInteger();
        stripe.when("POST /v1/checkout/sessions", stub -> {
            if (calls.incrementAndGet() == 1) {
                sleep(12_000);
            }
            return null;
        });
        HttpApiClient client = owner();
        HttpResponse<String> first = checkout(client);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(503);
        Map<String, Object> op = operations("create_checkout").getFirst();
        assertThat(op).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat((String) op.get("last_error")).contains("unreachable");
        assertThat(openTransactions()).isZero();

        Thread.sleep(5000);   // the stub has finished the first request by now (12 s after it began)
        HttpResponse<String> second = checkout(client);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        List<String> keys = stripe.keys("POST /v1/checkout/sessions");
        assertThat(keys).hasSize(2);
        assertThat(keys.get(1)).isEqualTo(keys.get(0));
        // One customer and one session at Stripe; the retry got the session the first attempt created.
        assertThat(stripe.created("cus_")).hasSize(1);
        assertThat(stripe.created("cs_test_")).hasSize(1);
        assertThat(second.body()).contains(stripe.created("cs_test_").getFirst());
        assertThat(operations("create_checkout")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 2));
        assertThat(operations("create_customer")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("status", "SUCCEEDED"));
    }

    @Test
    void aSlowCustomerCreationIsRetriedWithItsKeyToo() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        stripe.when("POST /v1/customers", stub -> {
            if (calls.incrementAndGet() == 1) {
                sleep(12_000);
            }
            return null;
        });
        HttpApiClient client = owner();
        assertThat(checkout(client).statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT provider_customer_id FROM business_subscriptions WHERE business_id = ?",
                String.class, shop)).isNull();
        Thread.sleep(5000);
        assertThat(checkout(client).statusCode()).isEqualTo(200);
        List<String> keys = stripe.keys("POST /v1/customers");
        assertThat(keys).hasSize(2);
        assertThat(keys.get(1)).isEqualTo(keys.get(0));
        assertThat(stripe.created("cus_")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT provider_customer_id FROM business_subscriptions WHERE business_id = ?",
                String.class, shop)).isEqualTo(stripe.created("cus_").getFirst());
    }

    @Test
    void anErrorAnswerSpendsTheKeyAndTheNextAttemptUsesANewOne() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        stripe.when("POST /v1/checkout/sessions", stub -> calls.incrementAndGet() == 1
                ? new Canned(500, "{\"error\":{\"type\":\"api_error\"}}") : null);
        HttpApiClient client = owner();
        assertThat(checkout(client).statusCode()).isEqualTo(503);
        assertThat(operations("create_checkout").getFirst()).containsEntry("status", "FAILED");
        assertThat(checkout(client).statusCode()).isEqualTo(200);
        List<String> keys = stripe.keys("POST /v1/checkout/sessions");
        assertThat(keys).hasSize(2);
        assertThat(keys.get(1)).isNotEqualTo(keys.get(0));
        assertThat(operations("create_checkout")).extracting(row -> row.get("status")).containsExactly("FAILED", "SUCCEEDED");
    }

    @Test
    void concurrentCheckoutsShareOneCustomerAndOneSession() throws Exception {
        stripe.when("POST /v1/customers", stub -> {
            sleep(300);
            return null;
        });
        stripe.when("POST /v1/checkout/sessions", stub -> {
            sleep(300);
            return null;
        });
        List<HttpApiClient> owners = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            owners.add(owner());
        }
        List<Future<HttpResponse<String>>> answers = new ArrayList<>();
        for (HttpApiClient client : owners) {
            answers.add(pool.submit(() -> checkout(client)));
        }
        Set<String> urls = new HashSet<>();
        for (Future<HttpResponse<String>> answer : answers) {
            HttpResponse<String> response = answer.get(20, TimeUnit.SECONDS);
            // A request whose key was still in flight at Stripe is refused (409, not recorded): 503, retry.
            assertThat(response.statusCode()).as(response.body()).isIn(200, 503);
            if (response.statusCode() == 200) {
                urls.add(response.body());
            }
        }
        // One customer for the business, however the requests interleaved.
        assertThat(stripe.created("cus_")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT provider_customer_id FROM business_subscriptions WHERE business_id = ?",
                String.class, shop)).isEqualTo(stripe.created("cus_").getFirst());
        // Requests that overlapped shared one key (one recorded operation, one session); a click after a
        // completed checkout starts a new one. Never two sessions for one key.
        List<String> keys = stripe.keys("POST /v1/checkout/sessions");
        long distinctKeys = keys.stream().distinct().count();
        assertThat(stripe.created("cs_test_")).hasSize((int) distinctKeys);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT idempotency_key) FROM billing_operations WHERE kind = 'create_checkout'",
                Long.class)).isEqualTo(distinctKeys);
        assertThat(urls).allSatisfy(url -> assertThat(stripe.created("cs_test_")).anySatisfy(id -> assertThat(url).contains(id)));
        // Whatever was refused succeeds on a retry.
        assertThat(checkout(owners.getFirst()).statusCode()).isEqualTo(200);
    }

    @Test
    void aCheckoutLeftOpenIsExpiredWhenItsBusinessIsDeleted() throws Exception {
        HttpApiClient client = owner();
        assertThat(checkout(client).statusCode()).isEqualTo(200);
        String session = stripe.created("cs_test_").getFirst();
        // The owner leaves the checkout page open and deletes the business.
        assertThat(client.deleteJson("/api/businesses/" + shop,
                "{\"password\":\"%s\",\"confirmName\":\"Slow Co\"}".formatted(TestAccounts.PASSWORD)).statusCode()).isEqualTo(204);
        assertThat(operations("expire_checkout")).singleElement()
                .satisfies(op -> assertThat(op).containsEntry("checkout_id", session).containsEntry("status", "PENDING"));
        // Stripe is down at first: retried, then done.
        stripe.when("POST /v1/checkout/sessions/" + session + "/expire", stub -> new Canned(503, "{}"));
        BillingCancellationWorker worker = api.bean(BillingCancellationWorker.class);
        assertThat(worker.processDue()).isEqualTo(1);
        assertThat(operations("expire_checkout").getFirst()).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        stripe.clear("POST /v1/checkout/sessions/" + session + "/expire");
        jdbc.update("UPDATE billing_operations SET next_attempt_at = now() WHERE kind = 'expire_checkout'");
        assertThat(worker.processDue()).isEqualTo(1);
        assertThat(stripe.sessionStatus(session)).isEqualTo("expired");
        assertThat(operations("expire_checkout").getFirst()).containsEntry("status", "SUCCEEDED");
        // Expiring again (already expired: Stripe answers an error) still counts as done.
        jdbc.update("UPDATE billing_operations SET status = 'PENDING', next_attempt_at = now() WHERE kind = 'expire_checkout'");
        assertThat(worker.processDue()).isEqualTo(1);
        assertThat(operations("expire_checkout").getFirst()).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void theEventWorkerFetchesOutsideATransactionAndOneSubscriptionAtATime() throws Exception {
        jdbc.update("""
                INSERT INTO business_subscriptions (business_id, provider, provider_customer_id, provider_subscription_id, plan, status)
                VALUES (?, 'stripe', 'cus_known', 'sub_known', 'pro', 'incomplete')
                """, shop);
        stripe.subscription("sub_known", "cus_known", shop, "active");
        for (String id : List.of("evt_1", "evt_2")) {
            jdbc.update("""
                    INSERT INTO billing_events (provider, event_id, event_type, object_type, object_id, subscription_id, customer_id, business_id)
                    VALUES ('stripe', ?, 'customer.subscription.updated', 'subscription', 'sub_known', 'sub_known', 'cus_known', ?)
                    """, id, shop);
        }
        CountDownLatch release = new CountDownLatch(1);
        stripe.when("GET /v1/subscriptions/sub_known", stub -> {
            stub.arrived.countDown();
            await(release);
            return null;
        });
        BillingEventWorker worker = api.bean(BillingEventWorker.class);
        Future<Integer> first = pool.submit(worker::processDue);
        assertThat(stripe.arrived.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(openTransactions()).isZero();
        assertThat(billingRowFree()).isTrue();
        // Another worker meanwhile: the subscription is leased, its event waits (not a failure).
        assertThat(worker.processDue()).isEqualTo(1);
        Map<String, Object> waiting = jdbc.queryForMap("""
                SELECT status, attempts, next_attempt_at > now() AS later FROM billing_events
                WHERE status = 'PENDING' AND locked_until IS NULL""");
        assertThat(waiting).containsEntry("status", "PENDING").containsEntry("attempts", 0).containsEntry("later", true);

        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM business_subscriptions WHERE business_id = ?", String.class, shop))
                .isEqualTo("active");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_subscription_leases", Long.class)).isZero();
        stripe.clear("GET /v1/subscriptions/sub_known");
        jdbc.update("UPDATE billing_events SET next_attempt_at = now() WHERE status = 'PENDING'");
        worker.processDue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_events WHERE status = 'PENDING'", Long.class)).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record Canned(int status, String body) {
    }

    /**
     * A minimal stand-in for the parts of Stripe's API the adapter uses. Idempotency like Stripe's: the
     * first result of a key is replayed for later requests with that key; a request whose key is still
     * being processed is refused with 409 {@code idempotency_error} and not recorded.
     */
    static final class StripeStub implements AutoCloseable {

        final CountDownLatch arrived = new CountDownLatch(1);
        private final HttpServer server;
        private final Map<String, Function<StripeStub, Canned>> behaviours = new ConcurrentHashMap<>();
        private final Map<String, Canned> byKey = new ConcurrentHashMap<>();
        private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
        private final Map<String, String> sessions = new ConcurrentHashMap<>();
        private final Map<String, String> subscriptions = new ConcurrentHashMap<>();
        private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private final List<String[]> keyed = Collections.synchronizedList(new ArrayList<>());
        private final List<String> createdIds = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger ids = new AtomicInteger();

        StripeStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", this::handle);
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        void when(String request, Function<StripeStub, Canned> behaviour) {
            behaviours.put(request, behaviour);
        }

        void clear(String request) {
            behaviours.remove(request);
        }

        List<String> requests() {
            return List.copyOf(requests);
        }

        List<String> keys(String request) {
            synchronized (keyed) {
                return keyed.stream().filter(k -> k[0].equals(request)).map(k -> k[1]).toList();
            }
        }

        List<String> created(String prefix) {
            synchronized (createdIds) {
                return createdIds.stream().filter(id -> id.startsWith(prefix)).toList();
            }
        }

        String sessionStatus(String id) {
            return sessions.get(id);
        }

        void subscription(String id, String customer, long businessId, String status) {
            subscriptions.put(id, """
                    {"id":"%s","object":"subscription","customer":"%s","status":"%s","livemode":false,
                     "cancel_at_period_end":false,"metadata":{"business_id":"%d"},
                     "items":{"data":[{"price":{"id":"%s"},"current_period_end":%d}]}}
                    """.formatted(id, customer, status, businessId, PRICE, Instant.now().plusSeconds(86400 * 30).getEpochSecond()));
        }

        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String request = method + " " + path;
            requests.add(request);
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            if (key != null) {
                keyed.add(new String[] {request, key});
            }
            exchange.getRequestBody().readAllBytes();
            Canned answer;
            if (key != null && byKey.containsKey(key)) {
                answer = byKey.get(key);
            } else if (key != null && !inFlight.add(key)) {
                answer = new Canned(409, "{\"error\":{\"type\":\"idempotency_error\",\"message\":\"in use\"}}");
            } else {
                try {
                    Function<StripeStub, Canned> behaviour = behaviours.get(request);
                    Canned forced = behaviour == null ? null : behaviour.apply(this);
                    answer = forced != null ? forced : answer(method, path);
                    if (key != null) {
                        byKey.put(key, answer);   // Stripe replays a key's first result, errors included
                    }
                } finally {
                    if (key != null) {
                        inFlight.remove(key);
                    }
                }
            }
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(answer.status(), body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException e) {
                // The client gave up (read timeout): Stripe would still have done the work.
            } finally {
                exchange.close();
            }
        }

        private Canned answer(String method, String path) {
            if (method.equals("POST") && path.equals("/v1/customers")) {
                String id = "cus_" + ids.incrementAndGet();
                createdIds.add(id);
                return new Canned(200, "{\"id\":\"%s\",\"object\":\"customer\"}".formatted(id));
            }
            if (method.equals("POST") && path.equals("/v1/checkout/sessions")) {
                String id = "cs_test_" + ids.incrementAndGet();
                createdIds.add(id);
                sessions.put(id, "open");
                return new Canned(200, "{\"id\":\"%s\",\"object\":\"checkout.session\",\"url\":\"https://checkout.stripe.com/c/pay/%s\"}"
                        .formatted(id, id));
            }
            if (method.equals("POST") && path.matches("/v1/checkout/sessions/[A-Za-z0-9_]+/expire")) {
                String id = path.split("/")[4];
                if (!"open".equals(sessions.get(id))) {
                    return new Canned(400, "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"not open\"}}");
                }
                sessions.put(id, "expired");
                return new Canned(200, "{\"id\":\"%s\",\"status\":\"expired\"}".formatted(id));
            }
            if (method.equals("GET") && path.startsWith("/v1/checkout/sessions/")) {
                String id = path.substring("/v1/checkout/sessions/".length());
                String status = sessions.get(id);
                return status == null ? new Canned(404, "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\"}}")
                        : new Canned(200, "{\"id\":\"%s\",\"object\":\"checkout.session\",\"status\":\"%s\",\"livemode\":false}".formatted(id, status));
            }
            if (method.equals("GET") && path.startsWith("/v1/subscriptions/")) {
                String body = subscriptions.get(path.substring("/v1/subscriptions/".length()));
                return body == null ? new Canned(404, "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\"}}")
                        : new Canned(200, body);
            }
            return new Canned(404, "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\"}}");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
