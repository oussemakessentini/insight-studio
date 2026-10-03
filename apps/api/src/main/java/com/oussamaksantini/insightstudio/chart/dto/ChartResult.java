package com.oussamaksantini.insightstudio.chart.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The figures of a chart (docs/chart-builder-contract.md §4).
 *
 * @param engine who computed them: {@code sql} or {@code cube} (also in {@code X-Report-Engine})
 * @param granularity {@code day}, {@code week} or {@code month} when grouped by time, else {@code null}
 * @param columns the group column (absent without grouping), then one column per metric in the
 *     definition's order
 * @param rows time buckets in order; or the top groups by the first metric (highest first, ties by
 *     label); or the single {@code total} row without grouping
 * @param totals every metric over the whole filtered period, not just the rows shown; orders count
 *     distinct orders
 * @param truncated whether more groups exist than {@code limit}
 * @param totalGroups groups (or buckets) before the limit was applied
 */
public record ChartResult(
        DateRange period,
        String timeZone,
        String currency,
        String engine,
        String groupBy,
        String granularity,
        List<Column> columns,
        List<Row> rows,
        Map<String, Object> totals,
        boolean truncated,
        int totalGroups,
        Instant generatedAt) {

    /**
     * @param type {@code dimension} or {@code metric}
     * @param unit {@code money} or {@code count} for metrics; omitted for the dimension
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Column(String key, String label, String type, String unit) {
    }

    /**
     * @param key bucket start date, store or product id, category name, or {@code total}
     * @param values metric key -> value (money with 2 decimals, counts as whole numbers)
     * @param partial a time bucket only partly inside the period (fewer days of sales)
     */
    public record Row(String key, String label, Map<String, Object> values, boolean partial) {
    }
}
