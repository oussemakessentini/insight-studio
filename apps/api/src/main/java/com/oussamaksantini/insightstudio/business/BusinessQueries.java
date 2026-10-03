package com.oussamaksantini.insightstudio.business;

import com.oussamaksantini.insightstudio.business.dto.TimeZonePreviewResponse;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Business and membership writes (plain JDBC). Every membership query names its business. */
@Repository
class BusinessQueries {

    private final NamedParameterJdbcTemplate jdbc;

    BusinessQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record MemberRow(long userId, String email, String displayName, Role role, Instant since) {
    }

    record BusinessRow(long id, String name, String slug, String currency, String timeZone, Instant createdAt) {
    }

    /** Months listed by a time zone preview at most. */
    static final int PREVIEW_MONTHS = 24;

    Optional<BusinessRow> find(long businessId) {
        return jdbc.query("SELECT id, name, slug, currency, time_zone, created_at FROM businesses WHERE id = :id",
                Map.of("id", businessId), (rs, i) -> new BusinessRow(rs.getLong("id"), rs.getString("name"),
                        rs.getString("slug"), rs.getString("currency"), rs.getString("time_zone"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant())).stream().findFirst();
    }

    /** Whether the business holds amounts in its currency: any product (list price) or sale. */
    boolean hasMonetaryData(long businessId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM products WHERE business_id = :businessId)
                    OR EXISTS (SELECT 1 FROM sales WHERE business_id = :businessId)
                """, Map.of("businessId", businessId), Boolean.class));
    }

    /**
     * Orders (receipts with at least one item, as in every report) bucketed by local day and month in
     * both zones; revenue is SUM(quantity * unit_price), like the reports.
     */
    private static final String ORDERS = """
            WITH orders AS (
                SELECT s.sold_at, SUM(si.quantity * si.unit_price) AS revenue
                FROM sales s
                JOIN sale_items si ON si.sale_id = s.id AND si.business_id = s.business_id
                WHERE s.business_id = :businessId
                GROUP BY s.id, s.sold_at
            )
            """;

    TimeZonePreviewResponse timeZonePreview(long businessId, String from, String to) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("businessId", businessId)
                .addValue("from", from)
                .addValue("to", to)
                .addValue("limit", PREVIEW_MONTHS);
        long[] counts = jdbc.queryForObject(ORDERS + """
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE (sold_at AT TIME ZONE :from)::date <> (sold_at AT TIME ZONE :to)::date) AS day,
                       COUNT(*) FILTER (WHERE date_trunc('month', sold_at AT TIME ZONE :from)
                                           <> date_trunc('month', sold_at AT TIME ZONE :to)) AS month
                FROM orders
                """, params, (rs, i) -> new long[] {rs.getLong("total"), rs.getLong("day"), rs.getLong("month")});
        List<TimeZonePreviewResponse.Month> months = jdbc.query(ORDERS + """
                , before AS (
                    SELECT to_char(sold_at AT TIME ZONE :from, 'YYYY-MM') AS month, SUM(revenue) AS revenue, COUNT(*) AS orders
                    FROM orders GROUP BY 1
                ), after AS (
                    SELECT to_char(sold_at AT TIME ZONE :to, 'YYYY-MM') AS month, SUM(revenue) AS revenue, COUNT(*) AS orders
                    FROM orders GROUP BY 1
                )
                SELECT COALESCE(b.month, a.month) AS month,
                       COALESCE(b.revenue, 0) AS revenue_before, COALESCE(a.revenue, 0) AS revenue_after,
                       COALESCE(b.orders, 0) AS orders_before, COALESCE(a.orders, 0) AS orders_after
                FROM before b FULL JOIN after a ON a.month = b.month
                WHERE COALESCE(b.revenue, 0) <> COALESCE(a.revenue, 0) OR COALESCE(b.orders, 0) <> COALESCE(a.orders, 0)
                ORDER BY 1 DESC
                LIMIT :limit
                """, params, (rs, i) -> new TimeZonePreviewResponse.Month(
                        rs.getString("month"),
                        money(rs.getBigDecimal("revenue_before")),
                        money(rs.getBigDecimal("revenue_after")),
                        rs.getLong("orders_before"),
                        rs.getLong("orders_after")));
        return new TimeZonePreviewResponse(from, to, counts[0], counts[1], counts[2], months);
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Inserts a business unless the slug is taken; returns its id, or empty on a slug conflict. */
    Optional<Long> insertBusiness(String name, String slug, String currency, String timeZone) {
        return jdbc.queryForList("""
                INSERT INTO businesses (name, slug, currency, time_zone) VALUES (:name, :slug, :currency, :timeZone)
                ON CONFLICT (slug) DO NOTHING
                RETURNING id
                """,
                new MapSqlParameterSource()
                        .addValue("name", name)
                        .addValue("slug", slug)
                        .addValue("currency", currency)
                        .addValue("timeZone", timeZone),
                Long.class).stream().findFirst();
    }

    void updateBusiness(long businessId, String name, String timeZone, String currency) {
        jdbc.update("UPDATE businesses SET name = :name, time_zone = :timeZone, currency = :currency WHERE id = :id",
                Map.of("id", businessId, "name", name, "timeZone", timeZone, "currency", currency));
    }

    /** Serialises membership changes of one business until the transaction ends (last-owner checks). */
    void lockBusiness(long businessId) {
        jdbc.queryForObject("SELECT id FROM businesses WHERE id = :id FOR UPDATE", Map.of("id", businessId), Long.class);
    }

    /** Adds a membership; returns {@code false} when the user is already a member. */
    boolean insertMembership(long userId, long businessId, Role role) {
        return jdbc.update("""
                INSERT INTO memberships (user_id, business_id, role) VALUES (:userId, :businessId, :role)
                ON CONFLICT (user_id, business_id) DO NOTHING
                """, Map.of("userId", userId, "businessId", businessId, "role", role.name())) == 1;
    }

    List<MemberRow> members(long businessId) {
        return jdbc.query("""
                SELECT u.id, u.email, u.display_name, m.role, m.created_at
                FROM memberships m JOIN users u ON u.id = m.user_id
                WHERE m.business_id = :businessId
                ORDER BY CASE m.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, lower(u.email), u.id
                """, Map.of("businessId", businessId), (rs, i) -> new MemberRow(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("display_name"),
                Role.valueOf(rs.getString("role")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()));
    }

    Optional<MemberRow> member(long businessId, long userId) {
        return jdbc.query("""
                SELECT u.id, u.email, u.display_name, m.role, m.created_at
                FROM memberships m JOIN users u ON u.id = m.user_id
                WHERE m.business_id = :businessId AND m.user_id = :userId
                """, Map.of("businessId", businessId, "userId", userId), (rs, i) -> new MemberRow(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("display_name"),
                Role.valueOf(rs.getString("role")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant())).stream().findFirst();
    }

    void updateRole(long businessId, long userId, Role role) {
        jdbc.update("UPDATE memberships SET role = :role WHERE business_id = :businessId AND user_id = :userId",
                Map.of("businessId", businessId, "userId", userId, "role", role.name()));
    }

    void deleteMembership(long businessId, long userId) {
        jdbc.update("DELETE FROM memberships WHERE business_id = :businessId AND user_id = :userId",
                Map.of("businessId", businessId, "userId", userId));
    }

    long countOwners(long businessId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM memberships WHERE business_id = :businessId AND role = 'OWNER'",
                Map.of("businessId", businessId), Long.class);
    }
}
