package com.oussamaksantini.insightstudio.accountdata;

import com.oussamaksantini.insightstudio.tenancy.Role;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC behind the previews, exports and deletions of businesses and accounts
 * (docs/account-management-contract.md §3, §4). Every statement names its business or account.
 */
@Repository
class AccountDataQueries {

    /** Business-scoped tables in the order a business deletion empties them (children first). */
    static final List<String> BUSINESS_TABLES = List.of(
            "dashboard_chart_refs", "dashboard_revisions", "dashboards",
            "chart_definition_revisions", "chart_definitions",
            "saved_reports", "sale_items", "sales", "import_batches", "products", "stores",
            "invitations", "memberships", "audit_events");

    private final NamedParameterJdbcTemplate jdbc;

    AccountDataQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record BusinessRow(long id, String name, String slug, String currency, String timeZone, OffsetDateTime createdAt) {
    }

    record MembershipRow(long businessId, String businessName, Role role, OffsetDateTime since, long memberCount,
            long ownerCount) {
    }

    record MemberRow(long userId, String displayName, Role role) {
    }

    Optional<BusinessRow> business(long businessId, boolean lock) {
        return jdbc.query("SELECT id, name, slug, currency, time_zone, created_at FROM businesses WHERE id = :id"
                        + (lock ? " FOR UPDATE" : ""),
                Map.of("id", businessId), (rs, i) -> new BusinessRow(rs.getLong("id"), rs.getString("name"),
                        rs.getString("slug"), rs.getString("currency"), rs.getString("time_zone"),
                        rs.getObject("created_at", OffsetDateTime.class))).stream().findFirst();
    }

    /** What deleting the business would remove, per kind (contract §4 preview). */
    Map<String, Long> businessCounts(long businessId) {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM memberships WHERE business_id = :b) AS members,
                    (SELECT COUNT(*) FROM invitations WHERE business_id = :b AND accepted_at IS NULL
                        AND revoked_at IS NULL AND expires_at > now()) AS pending_invitations,
                    (SELECT COUNT(*) FROM stores WHERE business_id = :b) AS stores,
                    (SELECT COUNT(*) FROM products WHERE business_id = :b) AS products,
                    (SELECT COUNT(*) FROM sales WHERE business_id = :b) AS sales,
                    (SELECT COUNT(*) FROM import_batches WHERE business_id = :b) AS imports,
                    (SELECT COUNT(*) FROM saved_reports WHERE business_id = :b) AS saved_reports,
                    (SELECT COUNT(*) FROM chart_definitions WHERE business_id = :b) AS charts,
                    (SELECT COUNT(*) FROM dashboards WHERE business_id = :b) AS dashboards,
                    (SELECT COUNT(*) FROM audit_events WHERE business_id = :b) AS audit_events
                """, Map.of("b", businessId), (rs, i) -> {
                    Map<String, Long> counts = new LinkedHashMap<>();
                    counts.put("members", rs.getLong("members"));
                    counts.put("pendingInvitations", rs.getLong("pending_invitations"));
                    counts.put("stores", rs.getLong("stores"));
                    counts.put("products", rs.getLong("products"));
                    counts.put("sales", rs.getLong("sales"));
                    counts.put("imports", rs.getLong("imports"));
                    counts.put("savedReports", rs.getLong("saved_reports"));
                    counts.put("charts", rs.getLong("charts"));
                    counts.put("dashboards", rs.getLong("dashboards"));
                    counts.put("auditEvents", rs.getLong("audit_events"));
                    return counts;
                });
    }

    List<MemberRow> members(long businessId) {
        return jdbc.query("""
                SELECT u.id, u.display_name, m.role
                FROM memberships m JOIN users u ON u.id = m.user_id
                WHERE m.business_id = :b
                ORDER BY CASE m.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, lower(u.display_name), u.id
                """, Map.of("b", businessId),
                (rs, i) -> new MemberRow(rs.getLong("id"), rs.getString("display_name"), Role.valueOf(rs.getString("role"))));
    }

    /**
     * Deletes every row of the business, children first, then the business itself (hard delete; the
     * report data version triggers fire for sales, items, products and stores). Returns the rows
     * deleted per table.
     */
    Map<String, Integer> deleteBusiness(long businessId) {
        Map<String, Object> params = Map.of("b", businessId);
        Map<String, Integer> deleted = new LinkedHashMap<>();
        for (String table : BUSINESS_TABLES) {
            deleted.put(table, jdbc.update("DELETE FROM " + table + " WHERE business_id = :b", params));
        }
        deleted.put("businesses", jdbc.update("DELETE FROM businesses WHERE id = :b", params));
        return deleted;
    }

    /** Pending emails of the business never go out: EXPIRED, body (with its link) erased. */
    int expireBusinessMail(long businessId) {
        return jdbc.update("""
                UPDATE mail_outbox SET status = 'EXPIRED', body = NULL, locked_until = NULL, finished_at = now()
                WHERE status = 'PENDING' AND business_id = :b
                """, Map.of("b", businessId));
    }

    /** Queues the Cube purge of a deleted business, at the report data version after its deletion. */
    long insertCubePurge(long businessId, String timeZone) {
        return jdbc.queryForObject("""
                INSERT INTO cube_purge_requests (business_id, time_zone, data_version)
                SELECT :b, :zone, version FROM report_data_version WHERE id = 1
                RETURNING id
                """, new MapSqlParameterSource().addValue("b", businessId).addValue("zone", timeZone), Long.class);
    }

    // ---------------------------------------------------------------- accounts

    /** Locks the user row (and, by id order, nothing else) until the transaction ends. */
    void lockUser(long userId) {
        jdbc.queryForList("SELECT id FROM users WHERE id = :id AND deleted_at IS NULL FOR UPDATE", Map.of("id", userId),
                Long.class);
    }

    /** Locks the businesses the user belongs to, in id order (the same order as membership changes take). */
    void lockMembershipBusinesses(long userId) {
        jdbc.queryForList("""
                SELECT b.id FROM businesses b
                WHERE b.id IN (SELECT business_id FROM memberships WHERE user_id = :u)
                ORDER BY b.id FOR UPDATE
                """, Map.of("u", userId), Long.class);
    }

    List<MembershipRow> memberships(long userId) {
        return jdbc.query("""
                SELECT m.business_id, b.name, m.role, m.created_at,
                       (SELECT COUNT(*) FROM memberships o WHERE o.business_id = m.business_id) AS member_count,
                       (SELECT COUNT(*) FROM memberships o WHERE o.business_id = m.business_id AND o.role = 'OWNER')
                           AS owner_count
                FROM memberships m JOIN businesses b ON b.id = m.business_id
                WHERE m.user_id = :u
                ORDER BY lower(b.name), b.id
                """, Map.of("u", userId), (rs, i) -> new MembershipRow(rs.getLong("business_id"), rs.getString("name"),
                Role.valueOf(rs.getString("role")), rs.getObject("created_at", OffsetDateTime.class),
                rs.getLong("member_count"), rs.getLong("owner_count")));
    }

    /** Charts, dashboards, saved reports and imports the account created (they stay with their businesses). */
    Map<String, Long> authoredCounts(long userId) {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM chart_definitions WHERE created_by = :u) AS charts,
                    (SELECT COUNT(*) FROM dashboards WHERE created_by = :u) AS dashboards,
                    (SELECT COUNT(*) FROM saved_reports WHERE created_by = :u) AS saved_reports,
                    (SELECT COUNT(*) FROM import_batches WHERE created_by = :u) AS imports
                """, Map.of("u", userId), (rs, i) -> {
                    Map<String, Long> counts = new LinkedHashMap<>();
                    counts.put("charts", rs.getLong("charts"));
                    counts.put("dashboards", rs.getLong("dashboards"));
                    counts.put("savedReports", rs.getLong("saved_reports"));
                    counts.put("imports", rs.getLong("imports"));
                    return counts;
                });
    }

    void deleteMemberships(long userId) {
        jdbc.update("DELETE FROM memberships WHERE user_id = :u", Map.of("u", userId));
    }

    /** Revokes every open invitation sent to the address, in any business. */
    int revokeInvitationsTo(String email) {
        return jdbc.update("""
                UPDATE invitations SET revoked_at = now()
                WHERE lower(email) = lower(:email) AND accepted_at IS NULL AND revoked_at IS NULL
                """, Map.of("email", email));
    }

    void deleteAccountTokens(long userId) {
        jdbc.update("DELETE FROM password_reset_tokens WHERE user_id = :u", Map.of("u", userId));
        jdbc.update("DELETE FROM email_verification_tokens WHERE user_id = :u", Map.of("u", userId));
    }

    /** Pending emails of the account (by owner or recipient) never go out: EXPIRED, body erased. */
    int expireAccountMail(long userId, String email) {
        return jdbc.update("""
                UPDATE mail_outbox SET status = 'EXPIRED', body = NULL, locked_until = NULL, finished_at = now()
                WHERE status = 'PENDING' AND (user_id = :u OR lower(recipient) = lower(:email))
                """, Map.of("u", userId, "email", email));
    }

    /**
     * Deletes every stored session of the account, on every instance, through Spring Session's
     * principal index ({@code spring_session.principal_name}, set by SessionAccountContextRepository;
     * attributes cascade).
     */
    int deleteSessions(String principalName) {
        return jdbc.update("DELETE FROM spring_session WHERE principal_name = :name", Map.of("name", principalName));
    }

    /**
     * Turns the user into a tombstone: every personal field erased, the id kept (charts, imports,
     * invitations and audit events point at it), and the session version bumped so that a session an
     * instance still holds in memory fails its next check.
     */
    void tombstone(long userId, String displayName) {
        jdbc.update("""
                UPDATE users SET email = NULL, password_hash = NULL, email_verified_at = NULL, last_sign_in_at = NULL,
                                 display_name = :name, deleted_at = now(), session_version = session_version + 1
                WHERE id = :u
                """, Map.of("u", userId, "name", displayName));
    }

    // ---------------------------------------------------------------- exports

    record AccountRow(long id, String email, String displayName, OffsetDateTime createdAt, OffsetDateTime emailVerifiedAt,
            OffsetDateTime lastSignInAt) {
    }

    Optional<AccountRow> account(long userId) {
        return jdbc.query("""
                SELECT id, email, display_name, created_at, email_verified_at, last_sign_in_at
                FROM users WHERE id = :u AND deleted_at IS NULL
                """, Map.of("u", userId), (rs, i) -> new AccountRow(rs.getLong("id"), rs.getString("email"),
                rs.getString("display_name"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("email_verified_at", OffsetDateTime.class),
                rs.getObject("last_sign_in_at", OffsetDateTime.class))).stream().findFirst();
    }

    /** Rows of {@code sql} (with {@code :u} the account) as maps of column name to value. */
    List<Map<String, Object>> authored(String sql, long userId) {
        return jdbc.queryForList(sql, Map.of("u", userId));
    }

    /** Streams the rows of a business-scoped query ({@code :b} is the business). */
    void stream(String sql, long businessId, RowCallbackHandler handler) {
        jdbc.query(sql, Map.of("b", businessId), handler);
    }
}
