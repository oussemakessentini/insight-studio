package com.oussamaksantini.insightstudio.business;

import com.oussamaksantini.insightstudio.tenancy.Role;
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

    void updateBusiness(long businessId, String name, String timeZone) {
        jdbc.update("UPDATE businesses SET name = :name, time_zone = :timeZone WHERE id = :id",
                Map.of("id", businessId, "name", name, "timeZone", timeZone));
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
                ORDER BY CASE m.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, lower(u.email)
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
