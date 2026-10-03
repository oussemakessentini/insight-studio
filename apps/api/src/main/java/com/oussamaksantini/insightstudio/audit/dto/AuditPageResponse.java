package com.oussamaksantini.insightstudio.audit.dto;

import java.util.List;

/** @param nextBefore pass as {@code before} for the next (older) page; {@code null} when there is none */
public record AuditPageResponse(List<AuditEventResponse> events, Long nextBefore) {
}
