package com.oussamaksantini.insightstudio.billing;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.billing.FakeBillingProvider.Delivery;
import com.oussamaksantini.insightstudio.retention.RetentionJob;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The webhook pipeline (docs/billing-contract.md §3, §5) with the fake provider: signatures, replays,
 * order, provider outages, payment failures, cancellations and business isolation.
 */
class BillingWebhookIntegrationTest extends BillingIntegrationTest {

    @Autowired
    RetentionJob retention;

    Shop shop;

    @BeforeEach
    void setUp() {
        shop = shop("Hook Shop");
    }

    private Delivery last(String type) {
        List<Delivery> all = fake().deliveries();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).type().equals(type)) {
                return all.get(i);
            }
        }
        throw new AssertionError("No " + type + " delivered");
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static String event(String id, String type, String object) {
        return "{\"id\":\"%s\",\"object\":\"event\",\"type\":\"%s\",\"livemode\":false,\"data\":{\"object\":%s}}"
                .formatted(id, type, object);
    }

    private static String subscriptionObject(String id, String customer, long businessId) {
        return "{\"id\":\"%s\",\"object\":\"subscription\",\"customer\":\"%s\",\"status\":\"active\",\"metadata\":{\"business_id\":\"%d\"}}"
                .formatted(id, customer, businessId);
    }

    /** Stores a fake subscription directly (as if created at the provider by someone else). */
    private void fakeSubscription(String id, String customer, long businessId, String status) {
        String data = """
                {"id":"%s","object":"subscription","customer":"%s","status":"%s","cancel_at_period_end":false,
                 "livemode":false,"metadata":{"business_id":"%d"},
                 "items":{"data":[{"price":{"id":"price_fake_pro"},"current_period_end":%d}]}}
                """.formatted(id, customer, status, businessId, Instant.now().plusSeconds(86_400).getEpochSecond());
        jdbc.update("INSERT INTO fake_billing_objects (id, kind, data) VALUES (?, 'subscription', CAST(? AS jsonb))", id, data);
    }

    // ---------------------------------------------------------------- signatures

    @Test
    void onlyCorrectlySignedRecentWebhooksAreAccepted() throws Exception {
        byte[] body = event("evt_sig_1", "customer.subscription.updated", subscriptionObject("sub_x", "cus_x", shop.id()))
                .getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        String secret = fake().webhookSecret();

        assertThat(postWebhook("fake", body, StripeSignature.header(body, now, "whsec_wrong"))).isEqualTo(400);
        byte[] tampered = new String(body, StandardCharsets.UTF_8).replace("sub_x", "sub_y").getBytes(StandardCharsets.UTF_8);
        assertThat(postWebhook("fake", tampered, StripeSignature.header(body, now, secret))).isEqualTo(400);
        assertThat(postWebhook("fake", body, StripeSignature.header(body, now - 400, secret))).isEqualTo(400);
        assertThat(postWebhook("fake", body, StripeSignature.header(body, now + 400, secret))).isEqualTo(400);
        assertThat(postWebhook("fake", body, null)).isEqualTo(400);
        String v1 = StripeSignature.header(body, now, secret).split(",v1=")[1];
        assertThat(postWebhook("fake", body, "t=" + now + ",v0=" + v1)).isEqualTo(400);
        assertThat(postWebhook("fake", body, "garbage")).isEqualTo(400);
        // Another provider's path does not exist here.
        assertThat(postWebhook("stripe", body, StripeSignature.header(body, now, secret))).isEqualTo(404);
        assertThat(count("SELECT COUNT(*) FROM billing_events")).isZero();

        // Valid, also among other signatures (rotated secrets) and with a v0 scheme alongside.
        String valid = StripeSignature.header(body, now - 200, secret);
        assertThat(postWebhook("fake", body, valid.replace(",v1=", ",v1=deadbeef,v0=abc,v1="))).isEqualTo(200);
        assertThat(events()).singleElement().satisfies(e -> {
            assertThat(e.get("event_id")).isEqualTo("evt_sig_1");
            assertThat(e.get("status")).isEqualTo("PENDING");
            assertThat(((Number) e.get("business_id")).longValue()).isEqualTo(shop.id());
        });
    }

    @Test
    void theWebhookEndpointNeedsNeitherASessionNorCsrf() throws Exception {
        // postWebhook sends neither a session nor a CSRF token.
        byte[] body = event("evt_open", "invoice.created", "{\"id\":\"in_1\",\"object\":\"invoice\"}").getBytes(StandardCharsets.UTF_8);
        assertThat(postWebhook("fake", body, fake().sign(body))).isEqualTo(200);
        eventWorker.processDue();
        assertThat(events().getFirst().get("status")).isEqualTo("IGNORED");
    }

    // ---------------------------------------------------------------- flows

    @Test
    void payingMovesTheBusinessToProOnceEvenWhenEventsAreDelivered_twice() throws Exception {
        String session = checkout(shop);
        int before = fake().deliveries().size();
        String answer = act(shop, session, "pay");
        assertThat((String) read(answer, "$.redirectUrl")).endsWith("/settings/billing?checkout=success");
        assertThat((String) read(answer, "$.status")).isEqualTo("complete");
        // Every delivery replayed: duplicates are no-ops.
        List<Delivery> delivered = fake().deliveries();
        for (Delivery delivery : delivered.subList(before, delivered.size())) {
            assertThat(replay(delivery)).isEqualTo(200);
        }
        assertThat(count("SELECT COUNT(*) FROM billing_events")).isEqualTo(3);
        eventWorker.processDue();
        assertThat(events()).allSatisfy(e -> assertThat(e.get("status")).isEqualTo("PROCESSED"));

        String billing = billing(shop);
        assertThat((String) read(billing, "$.plan.key")).isEqualTo("pro");
        assertThat((String) read(billing, "$.subscription.status")).isEqualTo("active");
        assertThat((Boolean) read(billing, "$.subscription.cancelAtPeriodEnd")).isFalse();
        assertThat((String) read(billing, "$.subscription.currentPeriodEnd")).isNotNull();
        assertThat(billing).doesNotContain("sub_fake_").doesNotContain("cus_fake_");
        // One plan change, audited once.
        List<Map<String, Object>> audit = jdbc.queryForList(
                "SELECT actor_user_id, target_type, details::text AS details FROM audit_events WHERE action = 'billing.plan_changed'");
        assertThat(audit).singleElement().satisfies(row -> {
            assertThat(row.get("actor_user_id")).isNull();
            assertThat(row.get("target_type")).isEqualTo("business");
            assertThat((String) row.get("details")).contains("\"from\": \"free\"").contains("\"to\": \"pro\"")
                    .contains("\"status\": \"active\"");
        });
        mvc.perform(get("/api/businesses/%d/audit?category=billing".formatted(shop.id())).with(as(shop.owner(), shop.id())))
                .andExpect(status().isOk());
    }

    @Test
    void aDeclinedCardStaysFreeAndPayCanBeRetried() throws Exception {
        String session = checkout(shop);
        String declined = act(shop, session, "decline");
        assertThat((String) read(declined, "$.status")).isEqualTo("declined");
        assertThat((Object) read(declined, "$.redirectUrl")).isNull();
        eventWorker.processDue();
        String billing = billing(shop);
        assertThat((String) read(billing, "$.plan.key")).isEqualTo("free");
        assertThat((String) read(billing, "$.subscription.status")).isEqualTo("incomplete");

        act(shop, session, "pay");
        eventWorker.processDue();
        assertThat(planOf(shop)).isEqualTo("pro");
        assertThat(count("SELECT COUNT(*) FROM fake_billing_objects WHERE kind = 'subscription'")).isEqualTo(1);
    }

    @Test
    void aFailedRenewalKeepsProWithAPaymentProblemUntilUnpaid() throws Exception {
        subscribe(shop);
        String portal = portal(shop);
        act(shop, portal, "fail-renewal");
        eventWorker.processDue();
        String billing = billing(shop);
        assertThat((String) read(billing, "$.plan.key")).isEqualTo("pro");
        assertThat((String) read(billing, "$.subscription.status")).isEqualTo("past_due");
        assertThat((Boolean) read(billing, "$.paymentProblem")).isTrue();

        act(shop, portal, "unpaid");
        eventWorker.processDue();
        billing = billing(shop);
        assertThat((String) read(billing, "$.plan.key")).isEqualTo("free");
        assertThat((Boolean) read(billing, "$.paymentProblem")).isTrue();

        act(shop, portal, "pay-outstanding");
        eventWorker.processDue();
        billing = billing(shop);
        assertThat((String) read(billing, "$.plan.key")).isEqualTo("pro");
        assertThat((Boolean) read(billing, "$.paymentProblem")).isFalse();
        assertThat(jdbc.queryForList("SELECT details ->> 'to' FROM audit_events WHERE action = 'billing.plan_changed' ORDER BY id",
                String.class)).containsExactly("pro", "free", "pro");
    }

    @Test
    void cancellingAtPeriodEndKeepsProUntilThePeriodEnds() throws Exception {
        subscribe(shop);
        String portal = portal(shop);
        String view = act(shop, portal, "cancel-at-period-end");
        assertThat((Boolean) read(view, "$.subscription.cancelAtPeriodEnd")).isTrue();
        eventWorker.processDue();
        String billing = billing(shop);
        assertThat((String) read(billing, "$.plan.key")).isEqualTo("pro");
        assertThat((Boolean) read(billing, "$.subscription.cancelAtPeriodEnd")).isTrue();

        act(shop, portal, "resume");
        eventWorker.processDue();
        assertThat((Boolean) read(billing(shop), "$.subscription.cancelAtPeriodEnd")).isFalse();

        act(shop, portal, "cancel-at-period-end");
        act(shop, portal, "end-period");
        eventWorker.processDue();
        billing = billing(shop);
        assertThat((String) read(billing, "$.plan.key")).isEqualTo("free");
        assertThat((String) read(billing, "$.subscription.status")).isEqualTo("canceled");
        assertThat((String) read(billing, "$.subscription.canceledAt")).isNotNull();
        // An ended subscription can't be managed any more; a new checkout is possible.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/billing/fake/%s/resume".formatted(portal)).with(as(shop.owner(), shop.id())))
                .andExpect(status().isConflict());
        subscribe(shop);
    }

    @Test
    void cancellingNowMovesToFreeAtOnce() throws Exception {
        subscribe(shop);
        act(shop, portal(shop), "cancel-now");
        eventWorker.processDue();
        assertThat(planOf(shop)).isEqualTo("free");
    }

    @Test
    void eventsProcessedOutOfOrderLeaveTheLatestState() throws Exception {
        String session = checkout(shop);
        // Nothing is delivered: the test delivers the events itself, in reverse order.
        jdbc.update("DELETE FROM billing_events");
        act(shop, session, "pay");
        act(shop, portal(shop), "cancel-now");
        jdbc.update("DELETE FROM billing_events");
        assertThat(replay(last("customer.subscription.deleted"))).isEqualTo(200);
        eventWorker.processDue();
        assertThat(planOf(shop)).isEqualTo("free");
        // The late "created" (and "checkout completed") re-read the provider: still canceled.
        assertThat(replay(last("customer.subscription.created"))).isEqualTo(200);
        assertThat(replay(last("checkout.session.completed"))).isEqualTo(200);
        assertThat(replay(last("invoice.paid"))).isEqualTo(200);
        eventWorker.processDue();
        String billing = billing(shop);
        assertThat((String) read(billing, "$.plan.key")).isEqualTo("free");
        assertThat((String) read(billing, "$.subscription.status")).isEqualTo("canceled");
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE action = 'billing.plan_changed'")).isZero();
    }

    @Test
    void aProviderOutageIsRetriedWithBackoffAndSurvivesANewWorker() throws Exception {
        act(shop, checkout(shop), "pay");
        BillingProvider down = new FailingProvider(fake());
        BillingEventWorker broken = new BillingEventWorker(named, transactions, down, plans, queries, audit, properties);
        long[] waits = {30, 60, 120, 240, 480, 960, 1920, 3600, 3600};
        long eventId = jdbc.queryForObject("SELECT MIN(id) FROM billing_events WHERE event_type = 'customer.subscription.created'", Long.class);
        jdbc.update("UPDATE billing_events SET next_attempt_at = now() + interval '1 day' WHERE id <> ?", eventId);
        for (int attempt = 1; attempt <= waits.length; attempt++) {
            assertThat(broken.processDue()).isEqualTo(1);
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT status, attempts, last_error, round(extract(epoch FROM next_attempt_at - now())) AS wait
                    FROM billing_events WHERE id = ?""", eventId);
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("attempts")).isEqualTo(attempt);
            assertThat((String) row.get("last_error")).contains("provider is down");
            assertThat(((Number) row.get("wait")).longValue()).isBetween(waits[attempt - 1] - 5, waits[attempt - 1]);
            assertThat(broken.processDue()).isZero();   // not due yet
            jdbc.update("UPDATE billing_events SET next_attempt_at = now() WHERE id = ?", eventId);
        }
        assertThat(planOf(shop)).isEqualTo("free");
        // A new instance (after a restart) with the provider back finishes it.
        BillingEventWorker restarted = new BillingEventWorker(named, transactions, provider, plans, queries, audit, properties);
        jdbc.update("UPDATE billing_events SET next_attempt_at = now()");
        restarted.processDue();
        assertThat(jdbc.queryForObject("SELECT status FROM billing_events WHERE id = ?", String.class, eventId)).isEqualTo("PROCESSED");
        assertThat(planOf(shop)).isEqualTo("pro");
    }

    @Test
    void anExpiredLeaseIsTakenOverByAnotherWorker() throws Exception {
        act(shop, checkout(shop), "pay");
        // A worker claimed everything and crashed.
        jdbc.update("UPDATE billing_events SET attempts = 1, locked_until = now() + interval '1 minute'");
        assertThat(eventWorker.processDue()).isZero();
        jdbc.update("UPDATE billing_events SET locked_until = now() - interval '1 second'");
        assertThat(eventWorker.processDue()).isEqualTo(3);
        assertThat(planOf(shop)).isEqualTo("pro");
    }

    // ---------------------------------------------------------------- isolation

    @Test
    void aWebhookForOneBusinessNeverChangesAnother() throws Exception {
        Shop other = shop("Other Shop");
        subscribe(shop);
        // A's subscription, with an event that claims B: refused.
        String subscription = subscriptionId(shop.id());
        String customer = jdbc.queryForObject("SELECT provider_customer_id FROM business_subscriptions WHERE business_id = ?",
                String.class, shop.id());
        byte[] body = event("evt_claims_b", "customer.subscription.updated", subscriptionObject(subscription, customer, other.id()))
                .getBytes(StandardCharsets.UTF_8);
        assertThat(postWebhook("fake", body, fake().sign(body))).isEqualTo(200);
        eventWorker.processDue();
        assertThat(jdbc.queryForObject("SELECT status FROM billing_events WHERE event_id = 'evt_claims_b'", String.class))
                .isEqualTo("IGNORED");
        assertThat(planOf(other)).isEqualTo("free");
        assertThat(count("SELECT COUNT(*) FROM business_subscriptions WHERE business_id = ?", other.id())).isZero();

        // A subscription whose metadata names B but whose customer is A's: refused.
        fakeSubscription("sub_fake_stolen", customer, other.id(), "active");
        body = event("evt_stolen", "customer.subscription.created", subscriptionObject("sub_fake_stolen", customer, other.id()))
                .getBytes(StandardCharsets.UTF_8);
        assertThat(postWebhook("fake", body, fake().sign(body))).isEqualTo(200);
        eventWorker.processDue();
        assertThat(jdbc.queryForObject("SELECT status FROM billing_events WHERE event_id = 'evt_stolen'", String.class))
                .isEqualTo("IGNORED");
        assertThat(planOf(other)).isEqualTo("free");

        // A subscription for A from another customer than A's: refused, A unchanged.
        fakeSubscription("sub_fake_foreign", "cus_fake_foreign", shop.id(), "canceled");
        body = event("evt_foreign", "customer.subscription.deleted", subscriptionObject("sub_fake_foreign", "cus_fake_foreign", shop.id()))
                .getBytes(StandardCharsets.UTF_8);
        assertThat(postWebhook("fake", body, fake().sign(body))).isEqualTo(200);
        eventWorker.processDue();
        assertThat(jdbc.queryForObject("SELECT status FROM billing_events WHERE event_id = 'evt_foreign'", String.class))
                .isEqualTo("IGNORED");
        assertThat(planOf(shop)).isEqualTo("pro");
        assertThat(subscriptionId(shop.id())).isEqualTo(subscription);
    }

    @Test
    void eventsForUnknownBusinessesSubscriptionsOrTypesAreIgnored() throws Exception {
        fakeSubscription("sub_fake_ghost", "cus_fake_ghost", 999_999, "active");
        byte[] ghost = event("evt_ghost", "customer.subscription.created", subscriptionObject("sub_fake_ghost", "cus_fake_ghost", 999_999))
                .getBytes(StandardCharsets.UTF_8);
        byte[] missing = event("evt_missing", "customer.subscription.updated", subscriptionObject("sub_fake_none", "cus_x", shop.id()))
                .getBytes(StandardCharsets.UTF_8);
        byte[] other = event("evt_other", "customer.created", "{\"id\":\"cus_1\",\"object\":\"customer\"}").getBytes(StandardCharsets.UTF_8);
        for (byte[] body : List.of(ghost, missing, other)) {
            assertThat(postWebhook("fake", body, fake().sign(body))).isEqualTo(200);
        }
        eventWorker.processDue();
        assertThat(events()).extracting(e -> e.get("status")).containsOnly("IGNORED");
        assertThat(count("SELECT COUNT(*) FROM business_subscriptions")).isZero();
    }

    // ---------------------------------------------------------------- retention

    @Test
    void finishedEventsArePurgedAfterThirtyDays() throws Exception {
        act(shop, checkout(shop), "pay");
        eventWorker.processDue();
        byte[] pending = event("evt_pending", "invoice.paid", "{\"id\":\"in_p\",\"object\":\"invoice\"}").getBytes(StandardCharsets.UTF_8);
        postWebhook("fake", pending, fake().sign(pending));
        jdbc.update("UPDATE billing_events SET processed_at = now() - interval '31 days', received_at = now() - interval '31 days'");
        jdbc.update("UPDATE billing_events SET processed_at = now() - interval '29 days' WHERE event_type = 'invoice.paid' AND event_id <> 'evt_pending'");
        jdbc.update("""
                INSERT INTO billing_cancellations (business_id, provider, provider_subscription_id, status, finished_at)
                VALUES (1, 'fake', 'sub_old', 'DONE', now() - interval '31 days'),
                       (2, 'fake', 'sub_recent', 'DONE', now() - interval '1 day'),
                       (3, 'fake', 'sub_pending', 'PENDING', NULL)
                """);
        Map<String, Integer> purged = retention.purge();
        assertThat(purged.get("billingEvents")).isEqualTo(2);
        assertThat(purged.get("billingCancellations")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_id FROM billing_events ORDER BY id", String.class)).hasSize(2).contains("evt_pending");
        assertThat(jdbc.queryForList("SELECT provider_subscription_id FROM billing_cancellations ORDER BY id", String.class))
                .containsExactly("sub_recent", "sub_pending");
    }

    @Test
    void theFakeSessionShowsItsSubscription() throws Exception {
        subscribe(shop);
        String portal = portal(shop);
        mvc.perform(get("/api/billing/fake/" + portal).with(as(shop.owner(), shop.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("portal"))
                .andExpect(jsonPath("$.plan.key").value("pro"))
                .andExpect(jsonPath("$.subscription.status").value("active"))
                .andExpect(jsonPath("$.returnUrl").value(org.hamcrest.Matchers.endsWith("/settings/billing")))
                .andExpect(jsonPath("$.actions").value(org.hamcrest.Matchers.hasItem("pay-outstanding")));
    }

    /** The fake, except that the provider is down for every call. */
    static final class FailingProvider implements BillingProvider {

        private final BillingProvider delegate;

        FailingProvider(BillingProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String createCustomer(long businessId, String businessName, String idempotencyKey) {
            throw down();
        }

        @Override
        public String createCheckout(long businessId, String customerId, Plan plan, String successUrl, String cancelUrl,
                String idempotencyKey) {
            throw down();
        }

        @Override
        public String createPortal(long businessId, String customerId, String returnUrl) {
            throw down();
        }

        @Override
        public Optional<SubscriptionState> fetchSubscription(String subscriptionId) {
            throw down();
        }

        @Override
        public Optional<CheckoutState> fetchCheckout(String checkoutId) {
            throw down();
        }

        @Override
        public void cancelSubscription(String subscriptionId) {
            throw down();
        }

        @Override
        public WebhookEvent verifyWebhook(byte[] rawBody, String signatureHeader) {
            return delegate.verifyWebhook(rawBody, signatureHeader);
        }

        private static BillingUnavailableException down() {
            return new BillingUnavailableException("The provider is down (test)");
        }
    }
}
