package com.oussamaksantini.insightstudio.customdashboard.dto;

import java.time.Instant;

/**
 * A dashboard in the list, without its layout.
 *
 * @param missingCount widgets whose chart was deleted
 */
public record DashboardSummaryResponse(
        long id,
        String name,
        int revision,
        int widgetCount,
        int missingCount,
        String updatedBy,
        Instant updatedAt) {
}
