package com.oussamaksantini.insightstudio.billing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain JDBC behind billing: subscriptions (V18), usage counts and the plan limit locks. */
@Repository
class BillingQueries {

    private final NamedParameterJdbcTemplate jdbc;

    BillingQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A {@code business_subscriptions} row. */
    record SubscriptionRow(long businessId, String provider, String customerId, String subscriptionId, String plan,
            String status, Instant currentPeriodEnd, boolean cancelAtPeriodEnd, Instant canceledAt) {
    }

    private static final String SUBSCRIPTION_COLUMNS = """
            business_id, provider, provider_customer_id, provider_subscription_id, plan, status, current_period_end,
            cancel_at_period_end, canceled_at
            """;

    Optional<SubscriptionRow> subscription(long businessId, boolean lock) {
        return jdbc.query("SELECT " + SUBSCRIPTION_COLUMNS + " FROM business_subscriptions WHERE business_id = :b"
                        + (lock ? " FOR UPDATE" : ""), Map.of("b", businessId), BillingQueries::subscriptionRow)
                .stream().findFirst();
    }

    /** The business linked to a provider customer, if any. */
    Optional<Long> businessOfCustomer(String provider, String customerId) {
        return jdbc.queryForList("""
                SELECT business_id FROM business_subscriptions WHERE provider = :p AND provider_customer_id = :c
                """, Map.of("p", provider, "c", customerId), Long.class).stream().findFirst();
    }

    /** The business linked to a provider subscription, if any. */
    Optional<Long> businessOfSubscription(String provider, String subscriptionId) {
        return jdbc.queryForList("""
                SELECT business_id FROM business_subscriptions WHERE provider = :p AND provider_subscription_id = :s
                """, Map.of("p", provider, "s", subscriptionId), Long.class).stream().findFirst();
    }

    /** Creates the business's row (status {@code none}) unless it exists, then locks it until the transaction ends. */
    SubscriptionRow lockOrCreate(long businessId, String provider) {
        jdbc.update("""
                INSERT INTO business_subscriptions (business_id, provider) VALUES (:b, :p)
                ON CONFLICT (business_id) DO NOTHING
                """, Map.of("b", businessId, "p", provider));
        return subscription(businessId, true).orElseThrow();
    }

    /** Links the business to a (new) provider customer; any earlier link to another provider is replaced. */
    void setCustomer(long businessId, String provider, String customerId) {
        jdbc.update("""
                UPDATE business_subscriptions
                SET provider_subscription_id = CASE WHEN provider = :p THEN provider_subscription_id END,
                    status = CASE WHEN provider = :p THEN status ELSE 'none' END,
                    plan = CASE WHEN provider = :p THEN plan ELSE 'free' END,
                    provider = :p, provider_customer_id = :c, updated_at = now()
                WHERE business_id = :b
                """, Map.of("b", businessId, "p", provider, "c", customerId));
    }

    /** Writes the subscription's state as the provider reported it. */
    void saveSubscription(SubscriptionRow row) {
        jdbc.update("""
                INSERT INTO business_subscriptions (business_id, provider, provider_customer_id, provider_subscription_id,
                    plan, status, current_period_end, cancel_at_period_end, canceled_at, synced_at)
                VALUES (:b, :p, :c, :s, :plan, :status, :end, :cancelAtEnd, :canceledAt, now())
                ON CONFLICT (business_id) DO UPDATE SET
                    provider = EXCLUDED.provider, provider_customer_id = EXCLUDED.provider_customer_id,
                    provider_subscription_id = EXCLUDED.provider_subscription_id, plan = EXCLUDED.plan,
                    status = EXCLUDED.status, current_period_end = EXCLUDED.current_period_end,
                    cancel_at_period_end = EXCLUDED.cancel_at_period_end, canceled_at = EXCLUDED.canceled_at,
                    synced_at = now(), updated_at = now()
                """, new MapSqlParameterSource()
                        .addValue("b", row.businessId())
                        .addValue("p", row.provider())
                        .addValue("c", row.customerId())
                        .addValue("s", row.subscriptionId())
                        .addValue("plan", row.plan())
                        .addValue("status", row.status())
                        .addValue("end", timestamp(row.currentPeriodEnd()))
                        .addValue("cancelAtEnd", row.cancelAtPeriodEnd())
                        .addValue("canceledAt", timestamp(row.canceledAt())));
    }

    /** Whether the business exists, locking its row (as a business deletion does) until the transaction ends. */
    boolean lockBusiness(long businessId) {
        return !jdbc.queryForList("SELECT id FROM businesses WHERE id = :b FOR UPDATE", Map.of("b", businessId), Long.class)
                .isEmpty();
    }

    Optional<String> businessSlug(long businessId) {
        return jdbc.queryForList("SELECT slug FROM businesses WHERE id = :b", Map.of("b", businessId), String.class)
                .stream().findFirst();
    }

    Optional<String> businessName(long businessId) {
        return jdbc.queryForList("SELECT name FROM businesses WHERE id = :b", Map.of("b", businessId), String.class)
                .stream().findFirst();
    }

    // ---------------------------------------------------------------- usage

    /**
     * Serialises creations of one resource in one business until the transaction ends: a distinct
     * advisory lock per (resource, business), so other resources and businesses are not blocked.
     */
    void lockResource(long businessId, PlanResource resource) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))",
                Map.of("key", "plan:" + resource.key() + ":" + businessId), (ResultSetExtractor<Void>) rs -> null);
    }

    /** The memberships of the business (accepting an invitation counts these only). */
    long members(long businessId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE business_id = :b", Map.of("b", businessId), Long.class);
    }

    /** What counts against {@code resource}'s limit now. */
    long used(long businessId, PlanResource resource) {
        String sql = switch (resource) {
            case MEMBERS -> """
                    SELECT (SELECT COUNT(*) FROM memberships WHERE business_id = :b)
                         + (SELECT COUNT(*) FROM invitations WHERE business_id = :b AND accepted_at IS NULL
                                AND revoked_at IS NULL AND expires_at > now())
                    """;
            case STORES -> "SELECT COUNT(*) FROM stores WHERE business_id = :b";
            case CHARTS -> "SELECT COUNT(*) FROM chart_definitions WHERE business_id = :b";
            case DASHBOARDS -> "SELECT COUNT(*) FROM dashboards WHERE business_id = :b";
            case IMPORTS_PER_MONTH -> IMPORTS_THIS_MONTH;
        };
        Long count = jdbc.queryForObject(sql, Map.of("b", businessId), Long.class);
        return count == null ? 0 : count;
    }

    /** Completed imports since the start of the current month in the business's time zone. */
    private static final String IMPORTS_THIS_MONTH = """
            SELECT COUNT(*) FROM import_batches i JOIN businesses b ON b.id = i.business_id
            WHERE i.business_id = :b AND i.status = 'IMPORTED'
              AND i.created_at >= (date_trunc('month', now() AT TIME ZONE b.time_zone) AT TIME ZONE b.time_zone)
            """;

    /** Every resource's usage in one statement (the Billing page). */
    Map<PlanResource, Long> usage(long businessId) {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM memberships WHERE business_id = :b)
                        + (SELECT COUNT(*) FROM invitations WHERE business_id = :b AND accepted_at IS NULL
                               AND revoked_at IS NULL AND expires_at > now()) AS members,
                    (SELECT COUNT(*) FROM stores WHERE business_id = :b) AS stores,
                    (SELECT COUNT(*) FROM chart_definitions WHERE business_id = :b) AS charts,
                    (SELECT COUNT(*) FROM dashboards WHERE business_id = :b) AS dashboards,
                    (""" + IMPORTS_THIS_MONTH + ") AS imports", Map.of("b", businessId), (rs, i) -> {
                    Map<PlanResource, Long> usage = new EnumMap<>(PlanResource.class);
                    usage.put(PlanResource.MEMBERS, rs.getLong("members"));
                    usage.put(PlanResource.STORES, rs.getLong("stores"));
                    usage.put(PlanResource.CHARTS, rs.getLong("charts"));
                    usage.put(PlanResource.DASHBOARDS, rs.getLong("dashboards"));
                    usage.put(PlanResource.IMPORTS_PER_MONTH, rs.getLong("imports"));
                    return usage;
                });
    }

    // ---------------------------------------------------------------- helpers

    static SubscriptionRow subscriptionRow(ResultSet rs, int i) throws SQLException {
        return new SubscriptionRow(
                rs.getLong("business_id"),
                rs.getString("provider"),
                rs.getString("provider_customer_id"),
                rs.getString("provider_subscription_id"),
                rs.getString("plan"),
                rs.getString("status"),
                instant(rs, "current_period_end"),
                rs.getBoolean("cancel_at_period_end"),
                instant(rs, "canceled_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }
}
