package com.oussamaksantini.insightstudio.business;

import com.oussamaksantini.insightstudio.tenancy.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Invitation rows (plain JDBC). Queries by id always name the business as well. */
@Repository
class InvitationQueries {

    private static final String COLUMNS = """
            i.id, i.business_id, b.name AS business_name, i.email, i.role, i.invited_by,
            u.display_name AS invited_by_name, i.created_at, i.expires_at,
            (i.accepted_at IS NULL AND i.revoked_at IS NULL AND i.expires_at > now()) AS open
            """;
    private static final String FROM = """
            FROM invitations i
            JOIN businesses b ON b.id = i.business_id
            JOIN users u ON u.id = i.invited_by
            """;

    private final NamedParameterJdbcTemplate jdbc;

    InvitationQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param open neither accepted, revoked nor expired
     */
    record InvitationRow(
            long id,
            long businessId,
            String businessName,
            String email,
            Role role,
            long invitedBy,
            String invitedByName,
            Instant createdAt,
            Instant expiresAt,
            boolean open) {
    }

    long insert(long businessId, String email, Role role, String tokenSha256, long invitedBy, Instant expiresAt) {
        return jdbc.queryForObject("""
                INSERT INTO invitations (business_id, email, role, token_sha256, invited_by, expires_at)
                VALUES (:businessId, :email, :role, :sha, :invitedBy, :expiresAt)
                RETURNING id
                """, new MapSqlParameterSource()
                        .addValue("businessId", businessId)
                        .addValue("email", email)
                        .addValue("role", role.name())
                        .addValue("sha", tokenSha256)
                        .addValue("invitedBy", invitedBy)
                        .addValue("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC)), Long.class);
    }

    /** Revokes the business's unfinished invitation (expired or not) for {@code email}, if any. */
    void revokeUnfinishedFor(long businessId, String email) {
        jdbc.update("""
                UPDATE invitations SET revoked_at = now()
                WHERE business_id = :businessId AND lower(email) = lower(:email)
                  AND accepted_at IS NULL AND revoked_at IS NULL
                """, Map.of("businessId", businessId, "email", email));
    }

    /** Open invitations of the business, newest first. */
    List<InvitationRow> open(long businessId) {
        return jdbc.query("SELECT " + COLUMNS + FROM + """
                WHERE i.business_id = :businessId
                  AND i.accepted_at IS NULL AND i.revoked_at IS NULL AND i.expires_at > now()
                ORDER BY i.created_at DESC, i.id DESC
                """, Map.of("businessId", businessId), InvitationQueries::row);
    }

    long countOpen(long businessId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM invitations
                WHERE business_id = :businessId AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()
                """, Map.of("businessId", businessId), Long.class);
    }

    Optional<InvitationRow> find(long businessId, long invitationId) {
        return jdbc.query("SELECT " + COLUMNS + FROM + "WHERE i.business_id = :businessId AND i.id = :id",
                Map.of("businessId", businessId, "id", invitationId), InvitationQueries::row).stream().findFirst();
    }

    /** The invitation for a token hash, locked until the transaction ends when {@code lock}. */
    Optional<InvitationRow> findByToken(String tokenSha256, boolean lock) {
        return jdbc.query("SELECT " + COLUMNS + FROM + "WHERE i.token_sha256 = :sha" + (lock ? " FOR UPDATE OF i" : ""),
                Map.of("sha", tokenSha256), InvitationQueries::row).stream().findFirst();
    }

    void revoke(long invitationId) {
        jdbc.update("UPDATE invitations SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL", Map.of("id", invitationId));
    }

    void markAccepted(long invitationId, long userId) {
        jdbc.update("UPDATE invitations SET accepted_at = now(), accepted_by = :userId WHERE id = :id",
                Map.of("id", invitationId, "userId", userId));
    }

    /** Whether an account with {@code email} is already a member of the business. */
    boolean isMemberEmail(long businessId, String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM memberships m JOIN users u ON u.id = m.user_id
                    WHERE m.business_id = :businessId AND lower(u.email) = lower(:email))
                """, Map.of("businessId", businessId, "email", email), Boolean.class));
    }

    private static InvitationRow row(ResultSet rs, int i) throws SQLException {
        return new InvitationRow(
                rs.getLong("id"),
                rs.getLong("business_id"),
                rs.getString("business_name"),
                rs.getString("email"),
                Role.valueOf(rs.getString("role")),
                rs.getLong("invited_by"),
                rs.getString("invited_by_name"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getBoolean("open"));
    }
}
