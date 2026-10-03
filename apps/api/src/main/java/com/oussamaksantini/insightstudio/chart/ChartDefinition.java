package com.oussamaksantini.insightstudio.chart;

import com.oussamaksantini.insightstudio.reporting.Granularity;
import com.oussamaksantini.insightstudio.savedreport.SavedRange;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A validated chart definition (docs/chart-builder-contract.md §1). Only {@link ChartValidator}
 * creates them, so every instance satisfies the rules of {@link ChartRules}; {@link #toJson} is the
 * normalized form stored in {@code chart_definition_revisions.definition} and returned by the API.
 *
 * @param granularity set exactly when {@code groupBy} is {@link ChartGroupBy#TIME}
 * @param limit top groups shown for store, product and category grouping; {@code null} otherwise
 */
public record ChartDefinition(
        int schemaVersion,
        String title,
        ChartVisualization visualization,
        List<ChartMetric> metrics,
        ChartGroupBy groupBy,
        Granularity granularity,
        SavedRange range,
        Filters filters,
        Integer limit,
        Engine engine) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Empty lists mean "all". Store and product ids belong to the definition's business. */
    public record Filters(List<Long> storeIds, List<String> categories, List<Long> productIds) {

        public static final Filters NONE = new Filters(List.of(), List.of(), List.of());

        /** Whether the chart counts only some line items (some products or categories). */
        public boolean filtersItems() {
            return !categories.isEmpty() || !productIds.isEmpty();
        }
    }

    /** Who computes the figures. */
    public enum Engine {
        SQL("sql"),
        CUBE("cube");

        private final String key;

        Engine(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Optional<Engine> fromKey(String key) {
            return Arrays.stream(values()).filter(e -> e.key.equals(key)).findFirst();
        }
    }

    public ChartDefinition withTitle(String newTitle) {
        return new ChartDefinition(schemaVersion, newTitle, visualization, metrics, groupBy, granularity, range,
                filters, limit, engine);
    }

    /** The first metric: the one that ranks groups and the only one of line, bar and pie charts. */
    public ChartMetric primaryMetric() {
        return metrics.getFirst();
    }

    /** The normalized JSON, with every field present and in the contract's order. */
    public ObjectNode toJson() {
        ObjectNode node = JSON.createObjectNode();
        node.put("schemaVersion", schemaVersion);
        node.put("title", title);
        node.put("visualization", visualization.key());
        ArrayNode metricKeys = node.putArray("metrics");
        metrics.forEach(m -> metricKeys.add(m.key()));
        node.put("groupBy", groupBy.key());
        if (granularity == null) {
            node.putNull("granularity");
        } else {
            node.put("granularity", granularity.param());
        }
        ObjectNode rangeNode = node.putObject("range");
        rangeNode.put("type", range.type());
        if (range.isRelative()) {
            rangeNode.put("preset", range.preset().code());
        } else {
            rangeNode.put("from", range.from().toString());
            rangeNode.put("to", range.to().toString());
        }
        ObjectNode filterNode = node.putObject("filters");
        ArrayNode stores = filterNode.putArray("storeIds");
        filters.storeIds().forEach(stores::add);
        ArrayNode categories = filterNode.putArray("categories");
        filters.categories().forEach(categories::add);
        ArrayNode products = filterNode.putArray("productIds");
        filters.productIds().forEach(products::add);
        if (limit == null) {
            node.putNull("limit");
        } else {
            node.put("limit", limit);
        }
        node.put("engine", engine.key());
        return node;
    }
}
