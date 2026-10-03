package com.oussamaksantini.insightstudio.customdashboard.dto;

import java.time.Instant;

/**
 * One saved revision of a dashboard, without its layout ({@code GET /api/dashboards/{id}?revision=n}
 * has it).
 *
 * @param createdBy display name of whoever saved it
 */
public record DashboardRevisionResponse(int revision, String name, int widgetCount, String createdBy, Instant createdAt) {
}
