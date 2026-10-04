package com.oussamaksantini.insightstudio.billing;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.billing.BillingWebhookIntegrationTest.FailingProvider;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deleting a business with a subscription (docs/billing-contract.md §8): the preview says it will be
 * canceled, the deletion queues the cancellation in its transaction, and the worker cancels it at the
 * provider, retrying while the provider fails; later webhooks for it are ignored.
 */
class BillingDeletionIntegrationTest extends BillingIntegrationTest {

    Shop shop;

    @BeforeEach
    void setUp() {
        shop = shop("Doomed Shop");
    }

    private void deleteBusiness() throws Exception {
        mvc.perform(json(delete("/api/businesses/" + shop.id()),
                "{\"password\":\"%s\",\"confirmName\":\"Doomed Shop\"}".formatted(TestAccounts.PASSWORD))
                .with(as(shop.owner(), shop.id()))).andExpect(status().isNoContent());
    }

    private String fakeStatus(String subscriptionId) {
        return jdbc.queryForObject("SELECT data ->> 'status' FROM fake_billing_objects WHERE id = ?", String.class, subscriptionId);
    }

    @Test
    void aBusinessWithoutASubscriptionQueuesNothing() throws Exception {
        mvc.perform(get("/api/businesses/%d/deletion-preview".formatted(shop.id())).with(as(shop.owner(), shop.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription").isEmpty());
        // An open checkout (a customer, no subscription) leaves nothing to cancel.
        checkout(shop);
        deleteBusiness();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_cancellations", Long.class)).isZero();
    }

    @Test
    void deletingAProBusinessCancelsItsSubscriptionEventually() throws Exception {
        subscribe(shop);
        String subscription = subscriptionId(shop.id());
        mvc.perform(get("/api/businesses/%d/deletion-preview".formatted(shop.id())).with(as(shop.owner(), shop.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.plan").value("pro"))
                .andExpect(jsonPath("$.subscription.status").value("active"))
                .andExpect(jsonPath("$.subscription.message")
                        .value("Your Pro subscription will be canceled; there is no refund for the current period."));

        deleteBusiness();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM billing_cancellations");
        assertThat(row.get("provider_subscription_id")).isEqualTo(subscription);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("business_id")).longValue()).isEqualTo(shop.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_subscriptions", Long.class)).isZero();

        // The provider is down: retried with backoff, never given up.
        BillingCancellationWorker broken = new BillingCancellationWorker(named, new FailingProvider(provider), properties, operations);
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(broken.processDue()).isEqualTo(1);
            Map<String, Object> pending = jdbc.queryForMap("""
                    SELECT status, attempts, last_error, round(extract(epoch FROM next_attempt_at - now())) AS wait
                    FROM billing_cancellations""");
            assertThat(pending.get("status")).isEqualTo("PENDING");
            assertThat(pending.get("attempts")).isEqualTo(attempt);
            assertThat((String) pending.get("last_error")).contains("provider is down");
            assertThat(((Number) pending.get("wait")).longValue()).isBetween(30L * (1L << (attempt - 1)) - 5, 30L * (1L << (attempt - 1)));
            assertThat(broken.processDue()).isZero();
            jdbc.update("UPDATE billing_cancellations SET next_attempt_at = now()");
        }
        assertThat(fakeStatus(subscription)).isEqualTo("active");

        // A new worker with the provider back cancels it.
        BillingCancellationWorker restarted = new BillingCancellationWorker(named, provider, properties, operations);
        assertThat(restarted.processDue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM billing_cancellations", String.class)).isEqualTo("DONE");
        assertThat(fakeStatus(subscription)).isEqualTo("canceled");

        // The provider's "deleted" webhook (and any replay) is ignored: the business is gone.
        eventWorker.processDue();
        assertThat(jdbc.queryForObject("SELECT status FROM billing_events WHERE event_type = 'customer.subscription.deleted'",
                String.class)).isEqualTo("IGNORED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_subscriptions", Long.class)).isZero();

        // Cancelling again is idempotent (already canceled at the provider).
        jdbc.update("UPDATE billing_cancellations SET status = 'PENDING', finished_at = NULL");
        assertThat(restarted.processDue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM billing_cancellations", String.class)).isEqualTo("DONE");
    }

    @Test
    void aCanceledSubscriptionNeedsNoCancellation() throws Exception {
        subscribe(shop);
        act(shop, portal(shop), "cancel-now");
        eventWorker.processDue();
        mvc.perform(get("/api/businesses/%d/deletion-preview".formatted(shop.id())).with(as(shop.owner(), shop.id())))
                .andExpect(jsonPath("$.subscription").isEmpty());
        deleteBusiness();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_cancellations", Long.class)).isZero();
    }

    /** The fake subscription created by a checkout session, as the provider sees it. */
    private String subscriptionOf(String session) {
        return jdbc.queryForObject("SELECT data ->> 'subscription' FROM fake_billing_objects WHERE id = ?", String.class, session);
    }

    @Test
    void aCheckoutPaidAfterItsBusinessWasDeletedIsCanceledAtTheProvider() throws Exception {
        String session = checkout(shop);
        deleteBusiness();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_cancellations", Long.class)).isZero();

        // The owner had the checkout page open and pays anyway (the fake's page API needs the business,
        // so the payment is made on the fake directly, as Stripe's page would).
        fake().act(session, "pay");
        String subscription = subscriptionOf(session);
        assertThat(fakeStatus(subscription)).isEqualTo("active");

        eventWorker.processDue();
        assertThat(events()).extracting(e -> e.get("status")).containsOnly("IGNORED");
        Map<String, Object> row = jdbc.queryForMap("SELECT business_id, provider_subscription_id, status FROM billing_cancellations");
        assertThat(((Number) row.get("business_id")).longValue()).isEqualTo(shop.id());
        assertThat(row.get("provider_subscription_id")).isEqualTo(subscription);

        assertThat(cancellationWorker.processDue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM billing_cancellations", String.class)).isEqualTo("DONE");
        assertThat(fakeStatus(subscription)).isEqualTo("canceled");
        // The provider's "deleted" event is ignored and queues nothing more.
        eventWorker.processDue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_cancellations", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_subscriptions", Long.class)).isZero();
    }

    @Test
    void aLateCheckoutIsCanceledEvenWithEventsDeliveredTwiceAndOutOfOrder() throws Exception {
        String session = checkout(shop);
        deleteBusiness();
        int before = fake().deliveries().size();
        fake().act(session, "pay");
        String subscription = subscriptionOf(session);
        var emitted = new java.util.ArrayList<>(fake().deliveries().subList(before, fake().deliveries().size()));
        // Start over: deliver them ourselves, reversed, each twice.
        jdbc.update("DELETE FROM billing_events");
        java.util.Collections.reverse(emitted);
        for (FakeBillingProvider.Delivery delivery : emitted) {
            assertThat(replay(delivery)).isEqualTo(200);
            assertThat(replay(delivery)).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_events", Long.class)).isEqualTo(emitted.size());
        eventWorker.processDue();
        assertThat(events()).extracting(e -> e.get("status")).containsOnly("IGNORED");
        assertThat(jdbc.queryForList("SELECT provider_subscription_id FROM billing_cancellations", String.class))
                .containsExactly(subscription);

        assertThat(cancellationWorker.processDue()).isEqualTo(1);
        assertThat(fakeStatus(subscription)).isEqualTo("canceled");
        // Replaying the old events once more changes nothing: the subscription is canceled now.
        for (FakeBillingProvider.Delivery delivery : emitted) {
            replay(delivery);
        }
        eventWorker.processDue();
        assertThat(cancellationWorker.processDue()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_cancellations", Long.class)).isEqualTo(1);
    }
}
