package com.oussamaksantini.insightstudio.billing;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Durable records of provider calls ({@code billing_operations}, Flyway V19): each call that creates
 * something at the provider is recorded with its idempotency key in a short transaction before the call
 * and completed in another one after it, so no transaction is open while the provider is called.
 *
 * <ul>
 *   <li>{@code create_customer}, {@code create_checkout}: run by the owner's checkout request. A request
 *       that finds a {@code PENDING} one (an earlier attempt timed out, or one is in flight) reuses its key:
 *       the provider then returns what the first attempt may already have created.</li>
 *   <li>{@code expire_checkout}: queued when a checkout session must no longer be payable (its business was
 *       deleted); run by {@link BillingCancellationWorker} until the provider confirms.</li>
 * </ul>
 */
@Component
class BillingOperations {

    /** Stripe keeps idempotency keys at least 24 hours: older keys are never reused. */
    static final Duration KEY_REUSE = Duration.ofHours(23);
    static final String CREATE_CUSTOMER = "create_customer";
    static final String CREATE_CHECKOUT = "create_checkout";
    static final String EXPIRE_CHECKOUT = "expire_checkout";
    private static final int MAX_ERROR = 500;

    record Operation(long id, long businessId, String provider, String kind, String idempotencyKey, String status,
            String plan, String customerId, String checkoutId, int attempts) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    BillingOperations(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * In the caller's transaction (which holds the business's billing row): the {@code PENDING} operation of
     * this kind for the business that may still be retried with its key, or a new one. Older pending ones
     * are abandoned (their key is too old to be honoured).
     */
    Operation pendingOrNew(long businessId, String provider, String kind, String plan, String customerId) {
        jdbc.update("""
                UPDATE billing_operations
                SET status = 'ABANDONED', finished_at = now(), updated_at = now(),
                    last_error = COALESCE(last_error, 'Idempotency key too old to reuse')
                WHERE business_id = :b AND provider = :p AND kind = :k AND status = 'PENDING'
                  AND created_at < now() - make_interval(secs => :reuse)
                """, params(businessId, provider, kind).addValue("reuse", KEY_REUSE.toSeconds()));
        // A pending checkout for another plan or customer would send different parameters with the same
        // key (the provider refuses that): it is abandoned too.
        jdbc.update("""
                UPDATE billing_operations
                SET status = 'ABANDONED', finished_at = now(), updated_at = now(),
                    last_error = COALESCE(last_error, 'Superseded by a checkout with other parameters')
                WHERE business_id = :b AND provider = :p AND kind = :k AND status = 'PENDING'
                  AND (plan IS DISTINCT FROM :plan OR customer_id IS DISTINCT FROM :c)
                """, params(businessId, provider, kind).addValue("plan", plan).addValue("c", customerId));
        Optional<Operation> pending = find("""
                WHERE business_id = :b AND provider = :p AND kind = :k AND status = 'PENDING'
                ORDER BY id DESC LIMIT 1
                """, params(businessId, provider, kind));
        if (pending.isPresent()) {
            return pending.get();
        }
        String key = "ins-" + (kind.equals(CREATE_CUSTOMER) ? "cu" : "co") + "-" + UUID.randomUUID();
        long id = jdbc.queryForObject("""
                INSERT INTO billing_operations (business_id, provider, kind, idempotency_key, plan, customer_id)
                VALUES (:b, :p, :k, :key, :plan, :c)
                RETURNING id
                """, params(businessId, provider, kind).addValue("key", key).addValue("plan", plan).addValue("c", customerId),
                Long.class);
        return find("WHERE id = :id", new MapSqlParameterSource("id", id)).orElseThrow();
    }

    /** Locks the operation's row (in the caller's transaction); empty when it no longer exists. */
    Optional<Operation> lock(long id) {
        return find("WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", id));
    }

    void succeeded(long id, String customerId, String checkoutId) {
        jdbc.update("""
                UPDATE billing_operations
                SET status = 'SUCCEEDED', customer_id = COALESCE(:c, customer_id), checkout_id = COALESCE(:s, checkout_id),
                    attempts = attempts + 1, last_error = NULL, finished_at = now(), updated_at = now()
                WHERE id = :id
                """, new MapSqlParameterSource("id", id).addValue("c", customerId).addValue("s", checkoutId));
    }

    /**
     * Records a failed call. {@code retrySameKey}: the outcome is unknown, so the operation stays
     * {@code PENDING} and the next attempt reuses its key; otherwise the key is spent ({@code FAILED}).
     */
    void failed(long id, String error, boolean retrySameKey) {
        jdbc.update("""
                UPDATE billing_operations
                SET status = CASE WHEN :same THEN 'PENDING' ELSE 'FAILED' END,
                    finished_at = CASE WHEN :same THEN NULL ELSE now() END,
                    attempts = attempts + 1, last_error = :error, updated_at = now()
                WHERE id = :id AND status = 'PENDING'
                """, new MapSqlParameterSource("id", id).addValue("same", retrySameKey).addValue("error", trim(error)));
    }

    void abandoned(long id, String reason, String checkoutId) {
        jdbc.update("""
                UPDATE billing_operations
                SET status = 'ABANDONED', checkout_id = COALESCE(:s, checkout_id), last_error = :reason,
                    finished_at = COALESCE(finished_at, now()), updated_at = now()
                WHERE id = :id AND status IN ('PENDING', 'SUCCEEDED', 'ABANDONED')
                """, new MapSqlParameterSource("id", id).addValue("reason", trim(reason)).addValue("s", checkoutId));
    }

    /** Queues the expiry of a checkout session (once per session). Returns whether one was queued. */
    boolean queueExpiry(long businessId, String provider, String checkoutId) {
        return jdbc.update("""
                INSERT INTO billing_operations (business_id, provider, kind, idempotency_key, checkout_id)
                VALUES (:b, :p, 'expire_checkout', :key, :s)
                ON CONFLICT (provider, checkout_id) WHERE kind = 'expire_checkout' DO NOTHING
                """, params(businessId, provider, EXPIRE_CHECKOUT).addValue("key", "ins-ex-" + UUID.randomUUID())
                        .addValue("s", checkoutId)) == 1;
    }

    /**
     * In a business deletion's transaction: every checkout session of the business that may still be open
     * is queued for expiry, and calls still pending are abandoned (the request running them sees that
     * afterwards and expires what it created). Returns how many expiries were queued.
     */
    int onBusinessDeleted(long businessId) {
        List<Map<String, Object>> sessions = jdbc.queryForList("""
                SELECT provider, checkout_id FROM billing_operations
                WHERE business_id = :b AND kind = 'create_checkout' AND checkout_id IS NOT NULL
                  AND status IN ('SUCCEEDED', 'ABANDONED') AND created_at > now() - interval '25 hours'
                """, Map.of("b", businessId));
        int queued = 0;
        for (Map<String, Object> s : sessions) {
            if (queueExpiry(businessId, (String) s.get("provider"), (String) s.get("checkout_id"))) {
                queued++;
            }
        }
        jdbc.update("""
                UPDATE billing_operations
                SET status = 'ABANDONED', last_error = 'Business deleted', finished_at = now(), updated_at = now()
                WHERE business_id = :b AND kind IN ('create_customer', 'create_checkout') AND status = 'PENDING'
                """, Map.of("b", businessId));
        return queued;
    }

    // ---------------------------------------------------------------- expire_checkout worker

    /** Claims one due expiry (one statement: committed at once). */
    Optional<Operation> claimExpiry(Duration lease) {
        return jdbc.query("""
                UPDATE billing_operations o
                SET attempts = o.attempts + 1, locked_until = now() + make_interval(secs => :lease), updated_at = now()
                FROM (
                    SELECT id FROM billing_operations
                    WHERE kind = 'expire_checkout' AND status = 'PENDING' AND next_attempt_at <= now()
                      AND (locked_until IS NULL OR locked_until < now())
                    ORDER BY next_attempt_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                ) due
                WHERE o.id = due.id
                RETURNING o.id, o.business_id, o.provider, o.kind, o.idempotency_key, o.status, o.plan, o.customer_id,
                          o.checkout_id, o.attempts
                """, new MapSqlParameterSource("lease", lease.toSeconds()), (rs, i) -> row(rs)).stream().findFirst();
    }

    void expiryDone(long id) {
        jdbc.update("""
                UPDATE billing_operations
                SET status = 'SUCCEEDED', locked_until = NULL, last_error = NULL, finished_at = now(), updated_at = now()
                WHERE id = :id
                """, Map.of("id", id));
    }

    void expiryFailed(long id, String error, Duration wait) {
        jdbc.update("""
                UPDATE billing_operations
                SET locked_until = NULL, last_error = :error, next_attempt_at = now() + make_interval(secs => :wait),
                    updated_at = now()
                WHERE id = :id
                """, new MapSqlParameterSource("id", id).addValue("error", trim(error)).addValue("wait", wait.toSeconds()));
    }

    // ---------------------------------------------------------------- helpers

    private Optional<Operation> find(String where, MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT id, business_id, provider, kind, idempotency_key, status, plan, customer_id, checkout_id, attempts
                FROM billing_operations
                """ + where, params, (rs, i) -> row(rs)).stream().findFirst();
    }

    private static Operation row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Operation(rs.getLong("id"), rs.getLong("business_id"), rs.getString("provider"), rs.getString("kind"),
                rs.getString("idempotency_key"), rs.getString("status"), rs.getString("plan"), rs.getString("customer_id"),
                rs.getString("checkout_id"), rs.getInt("attempts"));
    }

    private static MapSqlParameterSource params(long businessId, String provider, String kind) {
        return new MapSqlParameterSource().addValue("b", businessId).addValue("p", provider).addValue("k", kind);
    }

    private static String trim(String text) {
        return text == null || text.length() <= MAX_ERROR ? text : text.substring(0, MAX_ERROR);
    }
}
