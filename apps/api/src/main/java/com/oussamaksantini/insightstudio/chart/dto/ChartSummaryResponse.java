package com.oussamaksantini.insightstudio.chart.dto;

import java.time.Instant;
import java.util.List;

/** A chart in the list: what it shows, without the full definition. */
public record ChartSummaryResponse(
        long id,
        String title,
        String visualization,
        List<String> metrics,
        String groupBy,
        int revision,
        String updatedBy,
        Instant updatedAt) {
}
