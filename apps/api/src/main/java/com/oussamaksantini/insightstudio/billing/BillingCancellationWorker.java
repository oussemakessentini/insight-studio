package com.oussamaksantini.insightstudio.billing;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cancels, at the provider, the subscriptions of deleted businesses ({@code billing_cancellations},
 * written in the deletion's transaction by {@link BillingDeletion}; docs/billing-contract.md §8):
 * immediately, without proration. Same durability as {@link BillingEventWorker}: lease and
 * {@code FOR UPDATE SKIP LOCKED} on any instance, retried with backoff (30 s doubling to 1 h) until the
 * provider confirms; an already canceled or unknown subscription counts as done. A row of another
 * provider than the configured one waits (retried) for an instance configured with it.
 *
 * <p>It also expires, at the provider, the checkout sessions queued in {@code billing_operations}
 * ({@code expire_checkout}: a business deleted while a checkout was open or being created), with the same
 * retries. The provider is always called outside any database transaction.
 */
@Component
@ConditionalOnProperty(name = "insight.billing.worker.enabled", havingValue = "true", matchIfMissing = true)
public class BillingCancellationWorker {

    private static final Logger log = LoggerFactory.getLogger(BillingCancellationWorker.class);
    private static final String JOB = "billing_cancellations";

    private com.oussamaksantini.insightstudio.ops.JobHeartbeats heartbeats;

    /** Reports each round to the ops metrics (optional: absent in workers built by tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setHeartbeats(com.oussamaksantini.insightstudio.ops.JobHeartbeats heartbeats) {
        this.heartbeats = heartbeats;
    }

    private void beat(boolean ok) {
        if (heartbeats != null) {
            if (ok) {
                heartbeats.succeeded(JOB);
            } else {
                heartbeats.failed(JOB);
            }
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final BillingProvider provider;
    private final BillingProperties.Worker settings;
    private final BillingOperations operations;

    BillingCancellationWorker(NamedParameterJdbcTemplate jdbc, BillingProvider provider, BillingProperties properties,
            BillingOperations operations) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.settings = properties.worker();
        this.operations = operations;
    }

    record Claimed(long id, long businessId, String provider, String subscriptionId, int attempts) {
    }

    @Scheduled(fixedDelayString = "${insight.billing.worker.poll-interval:PT2S}",
            initialDelayString = "${insight.billing.worker.poll-interval:PT2S}")
    void poll() {
        try {
            processDue();
            beat(true);
        } catch (RuntimeException e) {
            beat(false);
            log.warn("Billing cancellation round failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Handles every due cancellation and checkout expiry; returns how many were handled (done or failed). */
    public int processDue() {
        int handled = 0;
        Claimed claimed;
        while ((claimed = claim()) != null) {
            process(claimed);
            handled++;
        }
        java.util.Optional<BillingOperations.Operation> expiry;
        while ((expiry = operations.claimExpiry(settings.lease())).isPresent()) {
            expire(expiry.get());
            handled++;
        }
        return handled;
    }

    private void expire(BillingOperations.Operation op) {
        try {
            if (!op.provider().equals(provider.name())) {
                throw new IllegalStateException("Provider " + op.provider() + " is not configured on this instance");
            }
            provider.expireCheckout(op.checkoutId());
            operations.expiryDone(op.id());
            log.info("Expired checkout session of deleted business {} at {} (operation {}).", op.businessId(),
                    op.provider(), op.id());
        } catch (RuntimeException e) {
            Duration wait = settings.retryDelay(op.attempts());
            operations.expiryFailed(op.id(), BillingEventWorker.describe(e), wait);
            if (op.attempts() >= settings.alertAfterAttempts()) {
                log.error("Expiring a checkout session of deleted business {} has failed {} times in a row; retrying in {}: "
                        + "{}. If it is paid meanwhile, its subscription is canceled automatically.", op.businessId(),
                        op.attempts(), wait, BillingEventWorker.describe(e));
            } else {
                log.warn("Expiring a checkout session of deleted business {} failed (attempt {}); retrying in {}: {}",
                        op.businessId(), op.attempts(), wait, BillingEventWorker.describe(e));
            }
        }
    }

    private Claimed claim() {
        return jdbc.query("""
                UPDATE billing_cancellations c
                SET attempts = c.attempts + 1, locked_until = now() + make_interval(secs => :lease)
                FROM (
                    SELECT id FROM billing_cancellations
                    WHERE status = 'PENDING' AND next_attempt_at <= now()
                      AND (locked_until IS NULL OR locked_until < now())
                    ORDER BY next_attempt_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                ) due
                WHERE c.id = due.id
                RETURNING c.id, c.business_id, c.provider, c.provider_subscription_id, c.attempts
                """, new MapSqlParameterSource().addValue("lease", settings.lease().toSeconds()),
                (rs, i) -> new Claimed(rs.getLong("id"), rs.getLong("business_id"), rs.getString("provider"),
                        rs.getString("provider_subscription_id"), rs.getInt("attempts")))
                .stream().findFirst().orElse(null);
    }

    private void process(Claimed cancellation) {
        try {
            if (!cancellation.provider().equals(provider.name())) {
                throw new IllegalStateException("Provider " + cancellation.provider() + " is not configured on this instance");
            }
            provider.cancelSubscription(cancellation.subscriptionId());
            jdbc.update("""
                    UPDATE billing_cancellations
                    SET status = 'DONE', finished_at = now(), locked_until = NULL, last_error = NULL
                    WHERE id = :id
                    """, new MapSqlParameterSource().addValue("id", cancellation.id()));
            log.info("Canceled the subscription of deleted business {} at {} (cancellation {}).",
                    cancellation.businessId(), cancellation.provider(), cancellation.id());
        } catch (RuntimeException e) {
            failed(cancellation, BillingEventWorker.describe(e));
        }
    }

    private void failed(Claimed cancellation, String error) {
        Duration wait = settings.retryDelay(cancellation.attempts());
        jdbc.update("""
                UPDATE billing_cancellations
                SET locked_until = NULL, last_error = :error, next_attempt_at = now() + make_interval(secs => :wait)
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", cancellation.id()).addValue("error", error)
                        .addValue("wait", wait.toSeconds()));
        if (cancellation.attempts() >= settings.alertAfterAttempts()) {
            log.error("Cancelling the subscription of deleted business {} has failed {} times in a row; retrying in {}: "
                    + "{}. It may still be charged until this succeeds.", cancellation.businessId(),
                    cancellation.attempts(), wait, error);
        } else {
            log.warn("Cancelling the subscription of deleted business {} failed (attempt {}); retrying in {}: {}",
                    cancellation.businessId(), cancellation.attempts(), wait, error);
        }
    }
}
