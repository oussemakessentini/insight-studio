package com.oussamaksantini.insightstudio.audit;

import com.oussamaksantini.insightstudio.audit.dto.AuditEventResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Reads audit events (plain JDBC). A deleted account (tombstone) is shown as "Deleted account". */
@Repository
public class AuditQueries {

    public static final String DELETED_ACCOUNT = "Deleted account";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SELECT = """
            SELECT e.id, e.business_id, e.action, e.actor_user_id,
                   CASE WHEN u.deleted_at IS NOT NULL THEN 'Deleted account' ELSE u.display_name END AS actor_name,
                   e.target_type, e.target_id, e.details::text AS details, e.created_at
            FROM audit_events e
            LEFT JOIN users u ON u.id = e.actor_user_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    AuditQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** An event with the business it belongs to. */
    public record Row(long businessId, AuditEventResponse event) {
    }

    /** At most {@code limit} events of the business older than {@code before}, newest first. */
    List<AuditEventResponse> page(long businessId, Long before, List<String> prefixes, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("businessId", businessId)
                .addValue("before", before)
                .addValue("limit", limit);
        StringBuilder sql = new StringBuilder(SELECT).append("WHERE e.business_id = :businessId");
        if (before != null) {
            sql.append(" AND e.id < :before");
        }
        if (prefixes != null) {
            List<String> likes = new ArrayList<>();
            for (int i = 0; i < prefixes.size(); i++) {
                likes.add("e.action LIKE :prefix" + i);
                params.addValue("prefix" + i, prefixes.get(i) + "%");
            }
            sql.append(" AND (").append(String.join(" OR ", likes)).append(")");
        }
        sql.append(" ORDER BY e.id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), params, (rs, i) -> event(rs));
    }

    /** Every event of the business, oldest first (business export). */
    public void forBusiness(long businessId, RowCallbackHandler handler) {
        jdbc.query(SELECT + "WHERE e.business_id = :businessId ORDER BY e.id",
                new MapSqlParameterSource("businessId", businessId), handler);
    }

    /** Every event the account performed, in any business, oldest first (account export). */
    public List<Row> byActor(long userId) {
        return jdbc.query(SELECT + "WHERE e.actor_user_id = :userId ORDER BY e.id",
                new MapSqlParameterSource("userId", userId), (rs, i) -> new Row(rs.getLong("business_id"), event(rs)));
    }

    public static AuditEventResponse event(ResultSet rs) throws SQLException {
        long actorId = rs.getLong("actor_user_id");
        AuditEventResponse.Actor actor = rs.wasNull() ? null : new AuditEventResponse.Actor(actorId, rs.getString("actor_name"));
        long targetId = rs.getLong("target_id");
        Long target = rs.wasNull() ? null : targetId;
        return new AuditEventResponse(rs.getLong("id"), rs.getString("action"), actor, rs.getString("target_type"), target,
                JSON.readTree(rs.getString("details")), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
