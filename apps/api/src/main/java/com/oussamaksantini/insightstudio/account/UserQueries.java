package com.oussamaksantini.insightstudio.account;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Users and password reset tokens (plain JDBC). Emails are matched case-insensitively. */
@Repository
public class UserQueries {

    private static final String COLUMNS = "id, email, password_hash, display_name, session_version";

    private static final RowMapper<UserRow> USER = (rs, i) -> new UserRow(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getInt("session_version"));

    private final NamedParameterJdbcTemplate jdbc;

    UserQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UserRow(long id, String email, String passwordHash, String displayName, int sessionVersion) {
    }

    public Optional<UserRow> findByEmail(String email) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE lower(email) = lower(:email)",
                Map.of("email", email), USER).stream().findFirst();
    }

    public Optional<UserRow> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE id = :id", Map.of("id", id), USER)
                .stream().findFirst();
    }

    /** The stored session version, or empty when the user no longer exists. */
    public Optional<Integer> sessionVersion(long id) {
        return jdbc.queryForList("SELECT session_version FROM users WHERE id = :id", Map.of("id", id), Integer.class)
                .stream().findFirst();
    }

    /** @throws org.springframework.dao.DuplicateKeyException when the email is taken */
    long insert(String email, String passwordHash, String displayName) {
        return jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, display_name) VALUES (:email, :hash, :name) RETURNING id",
                new MapSqlParameterSource()
                        .addValue("email", email)
                        .addValue("hash", passwordHash)
                        .addValue("name", displayName),
                Long.class);
    }

    void recordSignIn(long id) {
        jdbc.update("UPDATE users SET last_sign_in_at = now() WHERE id = :id", Map.of("id", id));
    }

    /** Stores a new password hash and increments the session version; returns the new version. */
    int updatePassword(long id, String passwordHash) {
        return jdbc.queryForObject("""
                UPDATE users SET password_hash = :hash, session_version = session_version + 1
                WHERE id = :id RETURNING session_version
                """, Map.of("id", id, "hash", passwordHash), Integer.class);
    }

    void insertResetToken(long userId, String tokenSha256, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO password_reset_tokens (user_id, token_sha256, expires_at) VALUES (:userId, :sha, :expiresAt)
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("sha", tokenSha256)
                        .addValue("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC)));
    }

    /** Marks every unused token of the user as used, so at most the newest link works. */
    void invalidateResetTokens(long userId) {
        jdbc.update("UPDATE password_reset_tokens SET used_at = now() WHERE user_id = :userId AND used_at IS NULL",
                Map.of("userId", userId));
    }

    /**
     * Atomically marks an unused, unexpired token as used and returns its user; empty when the token
     * is unknown, expired or already used. Two concurrent uses cannot both succeed.
     */
    Optional<Long> consumeResetToken(String tokenSha256) {
        return jdbc.queryForList("""
                UPDATE password_reset_tokens SET used_at = now()
                WHERE token_sha256 = :sha AND used_at IS NULL AND expires_at > now()
                RETURNING user_id
                """, Map.of("sha", tokenSha256), Long.class).stream().findFirst();
    }
}
