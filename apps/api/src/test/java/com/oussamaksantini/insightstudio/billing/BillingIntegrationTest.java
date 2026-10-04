package com.oussamaksantini.insightstudio.billing;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.TestcontainersConfiguration;
import com.oussamaksantini.insightstudio.audit.AuditLog;
import com.oussamaksantini.insightstudio.billing.FakeBillingProvider.Delivery;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import com.oussamaksantini.insightstudio.testsupport.TestSupportConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base of the billing tests: the full application with the <b>shipped plan limits</b> (Free: 3 members,
 * 2 stores, 10 charts, 3 dashboards, 10 imports a month) and the fake provider, on the shared test
 * database. A context of its own (the other tests raise the Free limits); the workers never run on
 * their own: tests call them.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "insight.mail.outbox.enabled=false", "insight.cube-purge.poll-interval=PT24H", "insight.retention.cron=-",
    "insight.billing.worker.poll-interval=PT24H", "insight.billing.provider=fake"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestSupportConfiguration.class})
abstract class BillingIntegrationTest {

    static final String CHART = """
            {"title": "%s", "visualization": "bar", "metrics": ["revenue"], "groupBy": "category",
             "range": {"type": "fixed", "from": "2026-01-01", "to": "2026-12-31"}}
            """;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NamedParameterJdbcTemplate named;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    MockMvc mvc;

    @Autowired
    BillingProvider provider;

    @Autowired
    BillingPlans plans;

    @Autowired
    BillingQueries queries;

    @Autowired
    AuditLog audit;

    @Autowired
    BillingProperties properties;

    @Autowired
    BillingOperations operations;

    @Autowired
    BillingEventWorker eventWorker;

    @Autowired
    BillingCancellationWorker cancellationWorker;

    SqlFixture fixture;
    TestAccounts accounts;

    @BeforeEach
    void cleanDatabase() {
        fixture = new SqlFixture(jdbc);
        fixture.clear();
        accounts = new TestAccounts(jdbc);
    }

    FakeBillingProvider fake() {
        return (FakeBillingProvider) provider;
    }

    /** A business with a verified OWNER. */
    record Shop(long id, TestUser owner) {
    }

    Shop shop(String name) {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        long id = fixture.business(name, slug, "USD", "America/New_York");
        return new Shop(id, accounts.member("owner@" + slug + ".test", id, com.oussamaksantini.insightstudio.tenancy.Role.OWNER));
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    static <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    /** Starts a checkout as the owner and returns the fake session id. */
    String checkout(Shop shop) throws Exception {
        MvcResult result = mvc.perform(json(post("/api/businesses/%d/billing/checkout".formatted(shop.id())), "{\"plan\":\"pro\"}")
                .with(as(shop.owner(), shop.id()))).andReturn();
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        String url = read(body(result), "$.url");
        assertThat(url).contains("/billing/fake/checkout/cs_fake_");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /** Opens the portal as the owner and returns the fake session id. */
    String portal(Shop shop) throws Exception {
        MvcResult result = mvc.perform(post("/api/businesses/%d/billing/portal".formatted(shop.id()))
                .with(as(shop.owner(), shop.id()))).andReturn();
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        String url = read(body(result), "$.url");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /** A fake page action as the owner; returns the answer. */
    String act(Shop shop, String session, String action) throws Exception {
        MvcResult result = mvc.perform(post("/api/billing/fake/%s/%s".formatted(session, action))
                .with(as(shop.owner(), shop.id()))).andReturn();
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return body(result);
    }

    /** Checkout, pay with the test card, process the webhooks: the business is on Pro. */
    void subscribe(Shop shop) throws Exception {
        act(shop, checkout(shop), "pay");
        eventWorker.processDue();
        assertThat(planOf(shop)).isEqualTo("pro");
    }

    /** {@code GET /api/businesses/{id}/billing} as the owner. */
    String billing(Shop shop) throws Exception {
        MvcResult result = mvc.perform(get("/api/businesses/%d/billing".formatted(shop.id())).with(as(shop.owner(), shop.id())))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return body(result);
    }

    String planOf(Shop shop) throws Exception {
        return read(billing(shop), "$.plan.key");
    }

    String subscriptionId(long businessId) {
        return jdbc.queryForObject("SELECT provider_subscription_id FROM business_subscriptions WHERE business_id = ?",
                String.class, businessId);
    }

    /** Posts a raw webhook to the real endpoint; returns the HTTP status. */
    int postWebhook(String provider, byte[] body, String signature) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/billing/webhooks/" + provider)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) {
            request.header(StripeSignature.HEADER, signature);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    int replay(Delivery delivery) throws Exception {
        return postWebhook("fake", delivery.body(), fake().sign(delivery.body()));
    }

    List<Map<String, Object>> events() {
        return jdbc.queryForList("SELECT event_id, event_type, status, attempts, last_error, business_id FROM billing_events ORDER BY id");
    }

    /** Runs {@code tasks} at once (released together) and returns their results. */
    static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
