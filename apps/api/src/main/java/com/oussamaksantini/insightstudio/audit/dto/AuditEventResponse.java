package com.oussamaksantini.insightstudio.audit.dto;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * One audit event (docs/account-management-contract.md §2).
 *
 * @param actor who made the change, {@code null} for the system; a deleted account is
 *     {@code {id, name: "Deleted account"}}
 */
public record AuditEventResponse(
        long id, String action, Actor actor, String targetType, Long targetId, JsonNode details, Instant createdAt) {

    public record Actor(long id, String name) {
    }
}
