package com.oussamaksantini.insightstudio.billing;

import com.oussamaksantini.insightstudio.billing.BillingQueries.SubscriptionRow;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Business deletion and billing (docs/billing-contract.md §8): what the deletion preview says, and the
 * cancellation queued in the deletion's own transaction ({@link BillingCancellationWorker} cancels it at
 * the provider). The subscription row itself goes with the business (foreign key cascade).
 */
@Component
public class BillingDeletion {

    private final BillingQueries queries;
    private final BillingPlans plans;
    private final NamedParameterJdbcTemplate jdbc;

    BillingDeletion(BillingQueries queries, BillingPlans plans, NamedParameterJdbcTemplate jdbc) {
        this.queries = queries;
        this.plans = plans;
        this.jdbc = jdbc;
    }

    /**
     * The subscription a deletion would cancel ({@code subscription} of the deletion preview).
     *
     * @param plan the subscription's plan key (e.g. {@code pro})
     * @param status the provider status (e.g. {@code active}, {@code past_due})
     * @param message what happens to it
     */
    public record SubscriptionToCancel(String plan, String status, String message) {
    }

    /** The subscription that deleting the business would cancel, if any. */
    public Optional<SubscriptionToCancel> preview(long businessId) {
        return queries.subscription(businessId, false).filter(BillingDeletion::live).map(row -> {
            String name = plans.find(row.plan()).map(Plan::name).orElse(row.plan());
            return new SubscriptionToCancel(row.plan(), row.status(),
                    "Your %s subscription will be canceled; there is no refund for the current period.".formatted(name));
        });
    }

    /**
     * In the deletion's transaction (after the business row is locked): queues the cancellation of the
     * business's subscription unless it has already ended. Returns whether one was queued.
     */
    public boolean onBusinessDeleted(long businessId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Cancellations are queued in the deletion's transaction.");
        }
        Optional<SubscriptionRow> row = queries.subscription(businessId, true).filter(BillingDeletion::live);
        if (row.isEmpty()) {
            return false;
        }
        return jdbc.update("""
                INSERT INTO billing_cancellations (business_id, provider, provider_subscription_id, provider_customer_id)
                VALUES (:b, :p, :s, :c)
                ON CONFLICT (provider, provider_subscription_id) DO NOTHING
                """, new MapSqlParameterSource()
                        .addValue("b", businessId)
                        .addValue("p", row.get().provider())
                        .addValue("s", row.get().subscriptionId())
                        .addValue("c", row.get().customerId())) == 1;
    }

    private static boolean live(SubscriptionRow row) {
        return row.subscriptionId() != null && !BillingPlans.ENDED.contains(row.status()) && !"none".equals(row.status());
    }
}
