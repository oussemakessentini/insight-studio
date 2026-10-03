package com.oussamaksantini.insightstudio.chart.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * What a chart may contain (docs/chart-builder-contract.md §4), generated from the same rule
 * definitions the server validates with, plus the business's own filter options.
 */
public record ChartCatalogResponse(
        List<Metric> metrics,
        List<Dimension> dimensions,
        List<Visualization> visualizations,
        List<Rule> rules,
        List<Preset> presets,
        List<Filter> filters,
        Map<String, Object> limits,
        List<String> engines,
        String defaultEngine) {

    /** @param additive whether groups add up to the total for every grouping */
    public record Metric(String key, String label, String unit, boolean additive) {
    }

    /** @param granularities bucket sizes, only for {@code time} */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Dimension(String key, String label, List<String> granularities) {
    }

    public record Visualization(String key, String label, List<String> groupBy, int minMetrics, int maxMetrics) {
    }

    /** A refused combination; absent parts match anything. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rule(String visualization, String groupBy, String metric, boolean allowed, String reason) {
    }

    public record Preset(String key, String label) {
    }

    /**
     * @param options the business's stores or categories; absent for products, which are searched
     *     with {@code search}
     * @param search endpoint to search products by name or SKU ({@code ?q=})
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Filter(String key, String label, List<Option> options, String search) {
    }

    /** @param value a store id (number) or a category name */
    public record Option(Object value, String label) {
    }
}
