package com.oussamaksantini.insightstudio.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes audit events ({@code audit_events}, Flyway V16; docs/account-management-contract.md §2).
 *
 * <ul>
 *   <li>Only inside the transaction of the change it describes: the event exists exactly when the
 *       change does (a rollback drops both). Called outside a transaction it fails.</li>
 *   <li>{@code details} may only hold the keys allowed for the action ({@link AuditAction}) with plain
 *       values (text, numbers, booleans, null); anything else is a programming error and fails the
 *       change rather than storing it.</li>
 * </ul>
 */
@Component
public class AuditLog {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_TEXT = 300;

    private final NamedParameterJdbcTemplate jdbc;

    AuditLog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param actorUserId who made the change ({@code null}: the system)
     * @param targetId the id of the action's target (business, invitation, user, import, chart, dashboard)
     * @param details the action's allowlisted details; {@code null} values are stored as JSON null
     */
    public void record(long businessId, Long actorUserId, AuditAction action, Long targetId, Map<String, ?> details) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Audit events are written in the transaction of the change.");
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        details.forEach((key, value) -> {
            if (!action.allowedDetails().contains(key)) {
                throw new IllegalArgumentException("'%s' is not an allowed detail of %s".formatted(key, action.action()));
            }
            if (value != null && !(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) {
                throw new IllegalArgumentException("Audit details are plain values");
            }
            clean.put(key, value instanceof String text && text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : value);
        });
        jdbc.update("""
                INSERT INTO audit_events (business_id, actor_user_id, action, target_type, target_id, details)
                VALUES (:businessId, :actor, :action, :targetType, :targetId, CAST(:details AS jsonb))
                """, new MapSqlParameterSource()
                        .addValue("businessId", businessId)
                        .addValue("actor", actorUserId)
                        .addValue("action", action.action())
                        .addValue("targetType", action.targetType())
                        .addValue("targetId", targetId)
                        .addValue("details", JSON.writeValueAsString(clean)));
    }

    public void record(long businessId, Long actorUserId, AuditAction action, Long targetId) {
        record(businessId, actorUserId, action, targetId, Map.of());
    }
}
