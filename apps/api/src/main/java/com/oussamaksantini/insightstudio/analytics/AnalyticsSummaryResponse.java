package com.oussamaksantini.insightstudio.analytics;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.math.BigDecimal;

/**
 * Totals for one business over {@code period}, computed by Cube with the same definitions as
 * {@code /api/dashboard/summary}.
 *
 * @param source always {@code "cube"}
 */
public record AnalyticsSummaryResponse(
        DateRange period,
        BigDecimal revenue,
        long orders,
        long unitsSold,
        BigDecimal averageOrderValue,
        String source) {
}
