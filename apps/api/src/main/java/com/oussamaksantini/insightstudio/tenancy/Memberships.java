package com.oussamaksantini.insightstudio.tenancy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read access to memberships: which businesses a user belongs to, and with which role. */
@Repository
public class Memberships {

    private final NamedParameterJdbcTemplate jdbc;

    Memberships(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A business the user belongs to, with the user's role in it. */
    public record MembershipView(
            long businessId, String name, String slug, String currency, String timeZone, Role role) {
    }

    /** The user's role in the business, or empty when the user is not a member. */
    public Optional<Role> role(long userId, long businessId) {
        return jdbc.queryForList("SELECT role FROM memberships WHERE user_id = :userId AND business_id = :businessId",
                        Map.of("userId", userId, "businessId", businessId), String.class)
                .stream().findFirst().map(Role::valueOf);
    }

    /** Every business the user belongs to, by name. */
    public List<MembershipView> forUser(long userId) {
        return jdbc.query("""
                SELECT b.id, b.name, b.slug, b.currency, b.time_zone, m.role
                FROM memberships m JOIN businesses b ON b.id = m.business_id
                WHERE m.user_id = :userId
                ORDER BY lower(b.name), b.id
                """, Map.of("userId", userId), (rs, i) -> new MembershipView(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("slug"),
                rs.getString("currency"),
                rs.getString("time_zone"),
                Role.valueOf(rs.getString("role"))));
    }

    public boolean hasMembers(long businessId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM memberships WHERE business_id = :businessId)",
                Map.of("businessId", businessId), Boolean.class));
    }
}
