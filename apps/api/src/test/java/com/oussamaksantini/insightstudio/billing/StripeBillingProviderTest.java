package com.oussamaksantini.insightstudio.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oussamaksantini.insightstudio.billing.BillingProperties.Limits;
import com.oussamaksantini.insightstudio.billing.BillingProperties.PlanSettings;
import com.oussamaksantini.insightstudio.billing.BillingProvider.BillingUnavailableException;
import com.oussamaksantini.insightstudio.billing.BillingProvider.InvalidWebhookException;
import com.oussamaksantini.insightstudio.billing.BillingProvider.SubscriptionState;
import com.oussamaksantini.insightstudio.billing.BillingProvider.WebhookEvent;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Stripe adapter's requests and answers against a local stub of Stripe's API (the JDK's HTTP
 * server; nothing leaves the machine), and its test-mode guard.
 */
class StripeBillingProviderTest {

    private static final String KEY = "sk_test_not_a_real_key";
    private static final String WEBHOOK_SECRET = "whsec_test_secret";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);

    record Recorded(String method, String path, Map<String, List<String>> headers, String body) {

        Map<String, String> form() {
            Map<String, String> form = new LinkedHashMap<>();
            for (String pair : body.split("&")) {
                if (!pair.isEmpty()) {
                    String[] parts = pair.split("=", 2);
                    form.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                }
            }
            return form;
        }

        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(e -> e.getValue().getFirst())
                    .findFirst().orElse(null);
        }
    }

    record Canned(int status, String body) {
    }

    HttpServer server;
    final List<Recorded> requests = new ArrayList<>();
    final Deque<Canned> answers = new ArrayDeque<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (requests) {
                requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        Map.copyOf(exchange.getRequestHeaders()), body));
            }
            Canned answer = answers.isEmpty() ? new Canned(500, "{}") : answers.poll();
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static BillingPlans plans(String provider) {
        Map<String, PlanSettings> plans = Map.of(
                "free", new PlanSettings("Free", "Free", null, new Limits(3, 2, 10, 3, 10)),
                "pro", new PlanSettings("Pro", "$29 / month", "price_123", new Limits(25, 50, 200, 50, 500)));
        return new BillingPlans(new BillingProperties(provider, plans, null, null, null));
    }

    private StripeBillingProvider stripe(String key) {
        BillingProperties.Stripe settings = new BillingProperties.Stripe(key, WEBHOOK_SECRET, "2025-03-31.basil",
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2), Duration.ofSeconds(2));
        return new StripeBillingProvider(settings, plans("stripe"), CLOCK);
    }

    @Test
    void refusesToStartWithoutATestModeKey() {
        for (String key : new String[] {"sk_live_abc", "rk_live_abc", "pk_test_abc", "", "whatever"}) {
            assertThatThrownBy(() -> stripe(key)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("test mode only").hasMessageNotContaining(key.isEmpty() ? "\u0000" : key);
        }
        stripe("sk_test_abc");
        stripe("rk_test_abc");
        BillingProperties.Stripe noSecret = new BillingProperties.Stripe(KEY, "", "2025-03-31.basil", "http://x",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThatThrownBy(() -> new StripeBillingProvider(noSecret, plans("stripe"), CLOCK)).hasMessageContaining("whsec_");
    }

    @Test
    void theStripeProviderNeedsAProPrice() {
        Map<String, PlanSettings> settings = Map.of(
                "free", new PlanSettings("Free", "Free", null, new Limits(3, 2, 10, 3, 10)),
                "pro", new PlanSettings("Pro", "$29", "", new Limits(25, 50, 200, 50, 500)));
        assertThatThrownBy(() -> new BillingPlans(new BillingProperties("stripe", settings, null, null, null)))
                .hasMessageContaining("BILLING_PRO_PRICE_ID");
    }

    @Test
    void createsCustomersCheckoutsAndPortalsWithTheDocumentedRequests() {
        StripeBillingProvider stripe = stripe(KEY);
        answers.add(new Canned(200, "{\"id\":\"cus_123\",\"object\":\"customer\"}"));
        answers.add(new Canned(200, "{\"id\":\"cs_test_1\",\"url\":\"https://checkout.stripe.com/c/pay/cs_test_1\"}"));
        answers.add(new Canned(200, "{\"id\":\"bps_1\",\"url\":\"https://billing.stripe.com/p/session/bps_1\"}"));

        assertThat(stripe.createCustomer(42, "Corner & Co", "key-1")).isEqualTo("cus_123");
        Plan pro = plans("stripe").find("pro").orElseThrow();
        assertThat(stripe.createCheckout(42, "cus_123", pro, "https://app/settings/billing?checkout=success",
                "https://app/settings/billing?checkout=canceled", "key-2")).isEqualTo("https://checkout.stripe.com/c/pay/cs_test_1");
        assertThat(stripe.createPortal(42, "cus_123", "https://app/settings/billing"))
                .isEqualTo("https://billing.stripe.com/p/session/bps_1");

        assertThat(requests).hasSize(3);
        Recorded customer = requests.get(0);
        assertThat(customer.method()).isEqualTo("POST");
        assertThat(customer.path()).isEqualTo("/v1/customers");
        assertThat(customer.header("Authorization")).isEqualTo("Bearer " + KEY);
        assertThat(customer.header("Stripe-Version")).isEqualTo("2025-03-31.basil");
        assertThat(customer.header("Idempotency-Key")).isEqualTo("key-1");
        assertThat(customer.header("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(customer.form()).containsExactlyInAnyOrderEntriesOf(Map.of("name", "Corner & Co", "metadata[business_id]", "42"));

        Recorded checkout = requests.get(1);
        assertThat(checkout.path()).isEqualTo("/v1/checkout/sessions");
        assertThat(checkout.header("Idempotency-Key")).isEqualTo("key-2");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("mode", "subscription");
        expected.put("line_items[0][price]", "price_123");
        expected.put("line_items[0][quantity]", "1");
        expected.put("customer", "cus_123");
        expected.put("client_reference_id", "42");
        expected.put("metadata[business_id]", "42");
        expected.put("subscription_data[metadata][business_id]", "42");
        expected.put("success_url", "https://app/settings/billing?checkout=success");
        expected.put("cancel_url", "https://app/settings/billing?checkout=canceled");
        assertThat(checkout.form()).containsExactlyEntriesOf(expected);

        Recorded portal = requests.get(2);
        assertThat(portal.path()).isEqualTo("/v1/billing_portal/sessions");
        assertThat(portal.header("Idempotency-Key")).isNotBlank();
        assertThat(portal.form()).containsExactlyInAnyOrderEntriesOf(Map.of("customer", "cus_123", "return_url", "https://app/settings/billing"));
    }

    @Test
    void readsSubscriptionsInBothApiShapes() {
        StripeBillingProvider stripe = stripe(KEY);
        answers.add(new Canned(200, """
                {"id":"sub_1","object":"subscription","customer":"cus_1","status":"past_due","cancel_at_period_end":true,
                 "canceled_at":null,"livemode":false,"metadata":{"business_id":"42"},
                 "items":{"data":[{"price":{"id":"price_123"},"current_period_end":1790000000}]}}
                """));
        answers.add(new Canned(200, """
                {"id":"sub_2","object":"subscription","customer":{"id":"cus_2"},"status":"canceled","cancel_at_period_end":false,
                 "canceled_at":1780000000,"current_period_end":1785000000,"metadata":{},
                 "items":{"data":[{"price":{"id":"price_other"}}]}}
                """));
        answers.add(new Canned(404, "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\"}}"));

        SubscriptionState first = stripe.fetchSubscription("sub_1").orElseThrow();
        assertThat(first).isEqualTo(new SubscriptionState("sub_1", "cus_1", "past_due", "price_123", 42L, true,
                Instant.ofEpochSecond(1790000000), null, false));
        SubscriptionState second = stripe.fetchSubscription("sub_2").orElseThrow();
        assertThat(second.customerId()).isEqualTo("cus_2");
        assertThat(second.currentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(1785000000));
        assertThat(second.canceledAt()).isEqualTo(Instant.ofEpochSecond(1780000000));
        assertThat(second.businessId()).isNull();
        assertThat(stripe.fetchSubscription("sub_3")).isEmpty();
        assertThat(requests).extracting(Recorded::method).containsOnly("GET");
        assertThat(requests).extracting(Recorded::path).containsExactly("/v1/subscriptions/sub_1", "/v1/subscriptions/sub_2",
                "/v1/subscriptions/sub_3");
        assertThatThrownBy(() -> stripe.fetchSubscription("../v1/customers")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsCheckoutSessions() {
        StripeBillingProvider stripe = stripe(KEY);
        answers.add(new Canned(200, "{\"id\":\"cs_1\",\"subscription\":\"sub_9\",\"customer\":\"cus_9\",\"client_reference_id\":\"7\",\"metadata\":{}}"));
        assertThat(stripe.fetchCheckout("cs_1").orElseThrow())
                .isEqualTo(new BillingProvider.CheckoutState("cs_1", "sub_9", "cus_9", 7L));
        assertThat(requests.getFirst().path()).isEqualTo("/v1/checkout/sessions/cs_1");
    }

    @Test
    void cancelsImmediatelyAndTreatsMissingOrEndedSubscriptionsAsDone() {
        StripeBillingProvider stripe = stripe(KEY);
        answers.add(new Canned(200, "{\"id\":\"sub_1\",\"status\":\"canceled\"}"));
        stripe.cancelSubscription("sub_1");
        answers.add(new Canned(404, "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\"}}"));
        stripe.cancelSubscription("sub_2");
        answers.add(new Canned(400, "{\"error\":{\"type\":\"invalid_request_error\"}}"));
        answers.add(new Canned(200, "{\"id\":\"sub_3\",\"status\":\"canceled\"}"));
        stripe.cancelSubscription("sub_3");
        assertThat(requests).extracting(r -> r.method() + " " + r.path()).containsExactly(
                "DELETE /v1/subscriptions/sub_1", "DELETE /v1/subscriptions/sub_2", "DELETE /v1/subscriptions/sub_3",
                "GET /v1/subscriptions/sub_3");
        assertThat(requests.getFirst().header("Idempotency-Key")).isNull();

        answers.add(new Canned(500, "{}"));
        assertThatThrownBy(() -> stripe.cancelSubscription("sub_4")).isInstanceOf(BillingUnavailableException.class);
    }

    @Test
    void anUnreachableOrFailingStripeIsUnavailable() {
        StripeBillingProvider stripe = stripe(KEY);
        answers.add(new Canned(500, "{\"error\":{\"type\":\"api_error\"}}"));
        assertThatThrownBy(() -> stripe.createCustomer(1, "x", "k")).isInstanceOf(BillingUnavailableException.class)
                .hasMessageNotContaining(KEY);
        server.stop(0);
        assertThatThrownBy(() -> stripe.fetchSubscription("sub_1")).isInstanceOf(BillingUnavailableException.class)
                .hasMessageNotContaining(KEY);
    }

    @Test
    void verifiesWebhooksAndRefusesLiveModeEvents() {
        StripeBillingProvider stripe = stripe(KEY);
        long now = CLOCK.instant().getEpochSecond();
        byte[] test = """
                {"id":"evt_1","type":"invoice.payment_failed","livemode":false,"data":{"object":{"id":"in_1","object":"invoice",
                 "customer":"cus_1","parent":{"subscription_details":{"subscription":"sub_1","metadata":{"business_id":"42"}}}}}}
                """.getBytes(StandardCharsets.UTF_8);
        WebhookEvent event = stripe.verifyWebhook(test, StripeSignature.header(test, now, WEBHOOK_SECRET));
        assertThat(event).isEqualTo(new WebhookEvent("evt_1", "invoice.payment_failed", false, "invoice", "in_1", "sub_1",
                "cus_1", 42L));

        byte[] live = "{\"id\":\"evt_2\",\"type\":\"invoice.paid\",\"livemode\":true,\"data\":{\"object\":{}}}"
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> stripe.verifyWebhook(live, StripeSignature.header(live, now, WEBHOOK_SECRET)))
                .isInstanceOf(InvalidWebhookException.class).hasMessageContaining("Live-mode");
        assertThatThrownBy(() -> stripe.verifyWebhook(test, StripeSignature.header(test, now, "whsec_other")))
                .isInstanceOf(InvalidWebhookException.class);
        assertThat(requests).isEmpty();
    }
}
