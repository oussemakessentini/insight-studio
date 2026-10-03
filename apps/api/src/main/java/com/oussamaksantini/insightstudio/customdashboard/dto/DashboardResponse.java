package com.oussamaksantini.insightstudio.customdashboard.dto;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A custom dashboard with the layout of one revision (the current one unless {@code ?revision=n}).
 *
 * @param name the name saved with that revision
 * @param widgets the layout's widgets in layout order, each with its chart as it is now
 * @param createdBy display name of the dashboard's creator
 * @param updatedBy display name of whoever saved that revision
 * @param updatedAt when that revision was saved
 */
public record DashboardResponse(
        long id,
        String name,
        int revision,
        JsonNode layout,
        List<DashboardWidgetResponse> widgets,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt) {
}
