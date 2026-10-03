package com.oussamaksantini.insightstudio.chart;

import static com.oussamaksantini.insightstudio.chart.ChartGroupBy.CATEGORY;
import static com.oussamaksantini.insightstudio.chart.ChartGroupBy.NONE;
import static com.oussamaksantini.insightstudio.chart.ChartGroupBy.PRODUCT;
import static com.oussamaksantini.insightstudio.chart.ChartGroupBy.STORE;
import static com.oussamaksantini.insightstudio.chart.ChartGroupBy.TIME;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Chart types with the groupings and metric counts they accept (docs/chart-builder-contract.md §2).
 * Line, bar and pie charts take exactly one metric, so a chart never needs two y-axes.
 */
public enum ChartVisualization {
    KPI("kpi", "KPI tiles", List.of(NONE), 1, 4),
    LINE("line", "Line chart", List.of(TIME), 1, 1),
    BAR("bar", "Bar chart", List.of(TIME, STORE, PRODUCT, CATEGORY), 1, 1),
    PIE("pie", "Pie chart", List.of(STORE, PRODUCT, CATEGORY), 1, 1),
    TABLE("table", "Table", List.of(NONE, TIME, STORE, PRODUCT, CATEGORY), 1, 4);

    private final String key;
    private final String label;
    private final List<ChartGroupBy> groupBy;
    private final int minMetrics;
    private final int maxMetrics;

    ChartVisualization(String key, String label, List<ChartGroupBy> groupBy, int minMetrics, int maxMetrics) {
        this.key = key;
        this.label = label;
        this.groupBy = groupBy;
        this.minMetrics = minMetrics;
        this.maxMetrics = maxMetrics;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** The groupings this chart type accepts, in the catalogue's order. */
    public List<ChartGroupBy> groupBy() {
        return groupBy;
    }

    public int minMetrics() {
        return minMetrics;
    }

    public int maxMetrics() {
        return maxMetrics;
    }

    public static Optional<ChartVisualization> fromKey(String key) {
        return Arrays.stream(values()).filter(v -> v.key.equals(key)).findFirst();
    }
}
