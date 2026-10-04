package com.oussamaksantini.insightstudio.billing;

import com.oussamaksantini.insightstudio.audit.AuditAction;
import com.oussamaksantini.insightstudio.audit.AuditLog;
import com.oussamaksantini.insightstudio.billing.BillingProvider.CheckoutState;
import com.oussamaksantini.insightstudio.billing.BillingProvider.SubscriptionState;
import com.oussamaksantini.insightstudio.billing.BillingQueries.SubscriptionRow;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes recorded webhook events (docs/billing-contract.md §5, step 3). Any number of API
 * instances may run it: events are claimed with {@code FOR UPDATE SKIP LOCKED} and a lease
 * ({@code locked_until}), like the Cube purge worker; a crashed worker's event is taken over once its
 * lease ends.
 *
 * <p>An event is never trusted for state: the worker resolves its subscription (from the event, or the
 * checkout session's subscription) and <b>fetches the subscription's current state from the provider</b>,
 * under a lock per subscription, then writes {@code business_subscriptions} from that state. Order of
 * arrival therefore does not matter: a late {@code created} after {@code deleted} re-reads "canceled".
 *
 * <p>The business comes from the subscription's {@code metadata.business_id}; it must agree with the
 * event's own reference and with the business already linked to that customer or subscription (if any),
 * otherwise the event is {@code IGNORED} (and logged). Events of unknown or deleted businesses, of
 * unhandled types and of subscriptions superseded by a newer one are {@code IGNORED} too.
 *
 * <p>Failures (provider down, ...) are retried after {@code retry-delay} (30 s) doubling up to
 * {@code max-retry-delay} (1 h), without limit; from the {@code alert-after-attempts}th (6th) failure in
 * a row each one is logged as an error. Plan changes write {@code billing.plan_changed}.
 */
@Component
@ConditionalOnProperty(name = "insight.billing.worker.enabled", havingValue = "true", matchIfMissing = true)
public class BillingEventWorker {

    private static final Logger log = LoggerFactory.getLogger(BillingEventWorker.class);
    private static final int MAX_ERROR = 500;

    static final Set<String> HANDLED = Set.of("checkout.session.completed", "customer.subscription.created",
            "customer.subscription.updated", "customer.subscription.deleted", "customer.subscription.paused",
            "customer.subscription.resumed", "invoice.paid", "invoice.payment_failed");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BillingProvider provider;
    private final BillingPlans plans;
    private final BillingQueries queries;
    private final AuditLog audit;
    private final BillingProperties.Worker settings;

    BillingEventWorker(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions, BillingProvider provider,
            BillingPlans plans, BillingQueries queries, AuditLog audit, BillingProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.provider = provider;
        this.plans = plans;
        this.queries = queries;
        this.audit = audit;
        this.settings = properties.worker();
    }

    record Claimed(long id, String provider, String eventId, String type, String objectType, String objectId,
            String subscriptionId, Long businessId, int attempts) {
    }

    /** How an event ended. */
    private record Result(String status, String note) {

        static Result processed() {
            return new Result("PROCESSED", null);
        }

        static Result ignored(String why) {
            return new Result("IGNORED", why);
        }
    }

    @Scheduled(fixedDelayString = "${insight.billing.worker.poll-interval:PT2S}",
            initialDelayString = "${insight.billing.worker.poll-interval:PT2S}")
    void poll() {
        try {
            processDue();
        } catch (RuntimeException e) {
            log.warn("Billing event round failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Processes every due event; returns how many were handled (processed, ignored or failed). */
    public int processDue() {
        if (!plans.enabled()) {
            return 0;
        }
        int handled = 0;
        Claimed claimed;
        while ((claimed = claim()) != null) {
            process(claimed);
            handled++;
        }
        return handled;
    }

    /** Claims one due event (one statement: the claim is committed at once). */
    private Claimed claim() {
        return jdbc.query("""
                UPDATE billing_events e
                SET attempts = e.attempts + 1, locked_until = now() + make_interval(secs => :lease)
                FROM (
                    SELECT id FROM billing_events
                    WHERE status = 'PENDING' AND next_attempt_at <= now()
                      AND (locked_until IS NULL OR locked_until < now())
                    ORDER BY next_attempt_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                ) due
                WHERE e.id = due.id
                RETURNING e.id, e.provider, e.event_id, e.event_type, e.object_type, e.object_id, e.subscription_id,
                          e.business_id, e.attempts
                """, new MapSqlParameterSource().addValue("lease", settings.lease().toSeconds()),
                (rs, i) -> new Claimed(rs.getLong("id"), rs.getString("provider"), rs.getString("event_id"),
                        rs.getString("event_type"), rs.getString("object_type"), rs.getString("object_id"),
                        rs.getString("subscription_id"), (Long) rs.getObject("business_id", Long.class),
                        rs.getInt("attempts")))
                .stream().findFirst().orElse(null);
    }

    private void process(Claimed event) {
        if (!event.provider().equals(provider.name())) {
            finish(event, Result.ignored("Provider " + event.provider() + " is not the configured one"));
            return;
        }
        if (!HANDLED.contains(event.type())) {
            finish(event, Result.ignored("Unhandled event type"));
            return;
        }
        try {
            String subscriptionId = event.subscriptionId();
            if (subscriptionId == null && "checkout.session.completed".equals(event.type()) && event.objectId() != null) {
                subscriptionId = provider.fetchCheckout(event.objectId()).map(CheckoutState::subscriptionId).orElse(null);
            }
            if (subscriptionId == null) {
                finish(event, Result.ignored("No subscription"));
                return;
            }
            String id = subscriptionId;
            transactions.executeWithoutResult(status -> {
                // One subscription at a time: the state fetched below is the one written, never an older one.
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))",
                        Map.of("key", "billing-subscription:" + provider.name() + ":" + id), (ResultSetExtractor<Void>) rs -> null);
                Optional<SubscriptionState> state = provider.fetchSubscription(id);
                Result result = state.isEmpty() ? Result.ignored("Unknown subscription") : apply(event, state.get());
                finish(event, result);
            });
        } catch (RuntimeException e) {
            failed(event, describe(e));
        }
    }

    /** Writes the subscription's state to its business (inside the subscription's lock). */
    private Result apply(Claimed event, SubscriptionState state) {
        String name = provider.name();
        if (state.livemode()) {
            return Result.ignored("Live-mode subscription refused");
        }
        Long businessId = state.businessId();
        if (businessId == null) {
            return Result.ignored("Subscription without metadata.business_id");
        }
        if (event.businessId() != null && !event.businessId().equals(businessId)) {
            log.warn("Billing event {} names business {} but its subscription belongs to business {}: ignored.",
                    event.eventId(), event.businessId(), businessId);
            return Result.ignored("Business mismatch between event and subscription");
        }
        if (!queries.lockBusiness(businessId)) {
            cancelOrphan(state);
            log.info("Billing event {} ({}) is for business {}, which does not exist (deleted?): ignored.",
                    event.eventId(), event.type(), businessId);
            return Result.ignored("Unknown or deleted business");
        }
        if (state.customerId() != null) {
            Optional<Long> linked = queries.businessOfCustomer(name, state.customerId());
            if (linked.isPresent() && linked.get() != businessId.longValue()) {
                log.warn("Billing event {}: customer is linked to business {}, subscription metadata says {}: ignored.",
                        event.eventId(), linked.get(), businessId);
                return Result.ignored("Customer belongs to another business");
            }
        }
        Optional<Long> owner = queries.businessOfSubscription(name, state.id());
        if (owner.isPresent() && owner.get() != businessId.longValue()) {
            log.warn("Billing event {}: subscription is linked to business {}, its metadata says {}: ignored.",
                    event.eventId(), owner.get(), businessId);
            return Result.ignored("Subscription belongs to another business");
        }
        if (state.status() == null || !BillingPlans.STATUSES.contains(state.status())) {
            log.warn("Billing event {}: unknown subscription status '{}': ignored.", event.eventId(), state.status());
            return Result.ignored("Unknown subscription status");
        }
        SubscriptionRow current = queries.subscription(businessId, true).orElse(null);
        boolean sameProvider = current != null && name.equals(current.provider());
        if (sameProvider && current.customerId() != null && !current.customerId().equals(state.customerId())) {
            log.warn("Billing event {}: business {} has another customer than the subscription's: ignored.",
                    event.eventId(), businessId);
            return Result.ignored("Customer mismatch");
        }
        if (sameProvider && current.subscriptionId() != null && !current.subscriptionId().equals(state.id())) {
            boolean incomingEntitled = BillingPlans.ENTITLED.contains(state.status());
            boolean currentEntitled = BillingPlans.ENTITLED.contains(current.status());
            if (!incomingEntitled && (currentEntitled || BillingPlans.ENDED.contains(state.status()))) {
                return Result.ignored("Superseded by the business's current subscription");
            }
        }
        Optional<Plan> plan = plans.forPrice(state.priceId());
        if (plan.isEmpty()) {
            log.warn("Billing event {}: subscription of business {} is for a price no plan is configured with; "
                    + "it counts as Free.", event.eventId(), businessId);
        }
        String planKey = plan.map(Plan::key).orElse(BillingPlans.FREE);
        Plan before = current == null ? plans.free() : plans.effective(current.provider(), current.plan(), current.status());
        queries.saveSubscription(new SubscriptionRow(businessId, name, state.customerId(), state.id(), planKey,
                state.status(), state.currentPeriodEnd(), state.cancelAtPeriodEnd(), state.canceledAt()));
        Plan after = plans.effective(name, planKey, state.status());
        if (!before.key().equals(after.key())) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("from", before.key());
            details.put("to", after.key());
            details.put("status", state.status());
            audit.record(businessId, null, AuditAction.BILLING_PLAN_CHANGED, businessId, details);
            log.info("Business {} moved from the {} plan to the {} plan (subscription status {}).", businessId,
                    before.key(), after.key(), state.status());
        } else {
            log.debug("Business {} subscription synced: {} ({}).", businessId, after.key(), state.status());
        }
        return Result.processed();
    }

    /**
     * A live subscription whose business is gone but whose customer belonged to a business deleted here
     * (a cancellation is recorded for that customer): it is queued for cancellation too, so a checkout
     * finished around the deletion is not left charging.
     */
    private void cancelOrphan(SubscriptionState state) {
        if (state.customerId() == null || BillingPlans.ENDED.contains(state.status())) {
            return;
        }
        int queued = jdbc.update("""
                INSERT INTO billing_cancellations (business_id, provider, provider_subscription_id, provider_customer_id)
                SELECT c.business_id, c.provider, :s, c.provider_customer_id
                FROM billing_cancellations c
                WHERE c.provider = :p AND c.provider_customer_id = :c
                LIMIT 1
                ON CONFLICT (provider, provider_subscription_id) DO NOTHING
                """, Map.of("p", provider.name(), "c", state.customerId(), "s", state.id()));
        if (queued > 0) {
            log.info("Queued the cancellation of a subscription of a deleted business's customer.");
        }
    }

    private void finish(Claimed event, Result result) {
        jdbc.update("""
                UPDATE billing_events
                SET status = :status, processed_at = now(), locked_until = NULL, last_error = :note
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", event.id()).addValue("status", result.status())
                        .addValue("note", result.note()));
    }

    private void failed(Claimed event, String error) {
        Duration wait = settings.retryDelay(event.attempts());
        jdbc.update("""
                UPDATE billing_events
                SET locked_until = NULL, last_error = :error, next_attempt_at = now() + make_interval(secs => :wait)
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", event.id()).addValue("error", error)
                        .addValue("wait", wait.toSeconds()));
        if (event.attempts() >= settings.alertAfterAttempts()) {
            log.error("Billing event {} ({}) has failed {} times in a row; retrying in {}: {}", event.eventId(),
                    event.type(), event.attempts(), wait, error);
        } else {
            log.warn("Billing event {} ({}) attempt {} failed; retrying in {}: {}", event.eventId(), event.type(),
                    event.attempts(), wait, error);
        }
    }

    static String describe(Exception e) {
        String text = (e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()))
                .replaceAll("\\s+", " ");
        return text.length() > MAX_ERROR ? text.substring(0, MAX_ERROR) : text;
    }
}
