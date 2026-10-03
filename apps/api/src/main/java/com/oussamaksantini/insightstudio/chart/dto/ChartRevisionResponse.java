package com.oussamaksantini.insightstudio.chart.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * One saved revision of a chart. The revision list omits {@code definition}.
 *
 * @param createdBy display name of whoever saved it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartRevisionResponse(int revision, JsonNode definition, String createdBy, Instant createdAt) {
}
