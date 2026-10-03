package com.oussamaksantini.insightstudio.chart.dto;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * A saved chart with the definition of its current revision.
 *
 * @param createdBy display name of the chart's creator
 * @param updatedBy display name of whoever saved the current revision
 */
public record ChartResponse(
        long id,
        String title,
        int revision,
        JsonNode definition,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt) {
}
