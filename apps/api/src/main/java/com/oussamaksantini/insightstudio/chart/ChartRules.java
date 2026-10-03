package com.oussamaksantini.insightstudio.chart;

import com.oussamaksantini.insightstudio.reporting.Granularity;
import com.oussamaksantini.insightstudio.reporting.ReportingContext;
import java.util.List;

/**
 * The compatibility rules and limits of chart definitions (docs/chart-builder-contract.md §2, §3).
 * The validator ({@link ChartValidator}) enforces exactly these and the catalogue
 * ({@code GET /api/charts/catalog}) publishes them, so the UI and the server cannot disagree.
 *
 * <p>Besides the groupings and metric counts of each {@link ChartVisualization}, {@link #DENIED}
 * lists the combinations of a visualization, grouping and metric that are refused, each with the
 * reason shown to the user. A {@code null} part matches anything.
 */
public final class ChartRules {

    /** Charts per business. */
    public static final int MAX_CHARTS = 200;
    public static final int MAX_TITLE_LENGTH = 120;
    /** Entries per filter list. */
    public static final int MAX_FILTER_VALUES = 50;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 50;
    public static final int DEFAULT_LIMIT = 10;
    /** Longest period of any chart. */
    public static final int MAX_RANGE_DAYS = ReportingContext.MAX_RANGE_DAYS;
    /** Longest period with daily buckets. */
    public static final int MAX_DAY_RANGE_DAYS = 366;
    /** Longest period with weekly buckets. */
    public static final int MAX_WEEK_RANGE_DAYS = ReportingContext.MAX_RANGE_DAYS;
    /** Rows of a time series (and therefore of any table). */
    public static final int MAX_TIME_BUCKETS = 400;
    public static final int SCHEMA_VERSION = 1;

    /** A refused combination; {@code null} parts match anything. */
    public record Denial(ChartVisualization visualization, ChartGroupBy groupBy, ChartMetric metric, String reason) {

        boolean matches(ChartVisualization v, ChartGroupBy g, ChartMetric m) {
            return (visualization == null || visualization == v)
                    && (groupBy == null || groupBy == g)
                    && (metric == null || metric == m);
        }
    }

    static final String AOV_SPLIT = "Average order value cannot be grouped by product or category: an order "
            + "can contain several products and categories, so its value cannot be split between them.";
    static final String PIE_AVERAGE = "A pie chart shows shares of a total, and average order value does not add "
            + "up to a total.";
    static final String PIE_ORDERS = "Orders cannot be shown as a pie by product or category: an order containing "
            + "several products or categories counts in each, so the slices would overlap.";

    public static final List<Denial> DENIED = List.of(
            new Denial(null, ChartGroupBy.PRODUCT, ChartMetric.AVERAGE_ORDER_VALUE, AOV_SPLIT),
            new Denial(null, ChartGroupBy.CATEGORY, ChartMetric.AVERAGE_ORDER_VALUE, AOV_SPLIT),
            new Denial(ChartVisualization.PIE, null, ChartMetric.AVERAGE_ORDER_VALUE, PIE_AVERAGE),
            new Denial(ChartVisualization.PIE, ChartGroupBy.PRODUCT, ChartMetric.ORDERS, PIE_ORDERS),
            new Denial(ChartVisualization.PIE, ChartGroupBy.CATEGORY, ChartMetric.ORDERS, PIE_ORDERS));

    private ChartRules() {
    }

    /** The first denial matching the combination, or {@code null} when it is allowed. */
    static Denial denial(ChartVisualization visualization, ChartGroupBy groupBy, ChartMetric metric) {
        return DENIED.stream().filter(d -> d.matches(visualization, groupBy, metric)).findFirst().orElse(null);
    }

    /** Longest period, in days, for a time series with {@code granularity}. */
    public static int maxRangeDays(Granularity granularity) {
        return switch (granularity) {
            case DAY -> MAX_DAY_RANGE_DAYS;
            case WEEK -> MAX_WEEK_RANGE_DAYS;
            case MONTH -> MAX_RANGE_DAYS;
        };
    }
}
