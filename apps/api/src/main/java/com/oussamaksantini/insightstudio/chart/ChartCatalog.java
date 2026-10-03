package com.oussamaksantini.insightstudio.chart;

import com.oussamaksantini.insightstudio.chart.ChartDefinition.Engine;
import com.oussamaksantini.insightstudio.chart.dto.ChartCatalogResponse;
import com.oussamaksantini.insightstudio.chart.dto.ChartCatalogResponse.Dimension;
import com.oussamaksantini.insightstudio.chart.dto.ChartCatalogResponse.Filter;
import com.oussamaksantini.insightstudio.chart.dto.ChartCatalogResponse.Metric;
import com.oussamaksantini.insightstudio.chart.dto.ChartCatalogResponse.Option;
import com.oussamaksantini.insightstudio.chart.dto.ChartCatalogResponse.Preset;
import com.oussamaksantini.insightstudio.chart.dto.ChartCatalogResponse.Rule;
import com.oussamaksantini.insightstudio.chart.dto.ChartCatalogResponse.Visualization;
import com.oussamaksantini.insightstudio.reporting.Granularity;
import com.oussamaksantini.insightstudio.savedreport.RelativePreset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code GET /api/charts/catalog}: generated from the enums and {@link ChartRules} that
 * {@link ChartValidator} enforces (the single source of truth), plus the business's stores and
 * categories as filter options and the engines this instance offers.
 */
@Component
class ChartCatalog {

    /** Where the UI searches products for the product filter. */
    static final String PRODUCT_SEARCH = "/api/products?q=";

    private final ChartLookups lookups;
    private final ChartEngines engines;

    ChartCatalog(ChartLookups lookups, ChartEngines engines) {
        this.lookups = lookups;
        this.engines = engines;
    }

    ChartCatalogResponse catalog(long businessId) {
        List<Metric> metrics = Arrays.stream(ChartMetric.values())
                .map(m -> new Metric(m.key(), m.label(), m.unit().key(), m.additive()))
                .toList();
        List<Dimension> dimensions = Arrays.stream(ChartGroupBy.values())
                .map(g -> new Dimension(g.key(), g.label(), g == ChartGroupBy.TIME
                        ? Arrays.stream(Granularity.values()).map(Granularity::param).toList()
                        : null))
                .toList();
        List<Visualization> visualizations = Arrays.stream(ChartVisualization.values())
                .map(v -> new Visualization(v.key(), v.label(), v.groupBy().stream().map(ChartGroupBy::key).toList(),
                        v.minMetrics(), v.maxMetrics()))
                .toList();
        List<Rule> rules = ChartRules.DENIED.stream()
                .map(d -> new Rule(
                        d.visualization() == null ? null : d.visualization().key(),
                        d.groupBy() == null ? null : d.groupBy().key(),
                        d.metric() == null ? null : d.metric().key(),
                        false,
                        d.reason()))
                .toList();
        List<Preset> presets = Arrays.stream(RelativePreset.values())
                .map(p -> new Preset(p.code(), p.label()))
                .toList();
        List<Option> stores = lookups.stores(businessId).entrySet().stream()
                .map(e -> new Option(e.getKey(), e.getValue()))
                .toList();
        List<Option> categories = lookups.categories(businessId).stream()
                .map(name -> new Option(name, name))
                .toList();
        List<Filter> filters = List.of(
                new Filter("storeIds", "Stores", stores, null),
                new Filter("categories", "Categories", categories, null),
                new Filter("productIds", "Products", null, PRODUCT_SEARCH));
        return new ChartCatalogResponse(metrics, dimensions, visualizations, rules, presets, filters, limits(),
                engines.available().stream().map(Engine::key).toList(), Engine.SQL.key());
    }

    private static Map<String, Object> limits() {
        Map<String, Object> rangeDays = new LinkedHashMap<>();
        for (Granularity granularity : Granularity.values()) {
            rangeDays.put(granularity.param(), ChartRules.maxRangeDays(granularity));
        }
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxRangeDays", ChartRules.MAX_RANGE_DAYS);
        limits.put("maxRangeDaysByGranularity", rangeDays);
        limits.put("maxTimeBuckets", ChartRules.MAX_TIME_BUCKETS);
        limits.put("minLimit", ChartRules.MIN_LIMIT);
        limits.put("maxLimit", ChartRules.MAX_LIMIT);
        limits.put("defaultLimit", ChartRules.DEFAULT_LIMIT);
        limits.put("maxFilterValues", ChartRules.MAX_FILTER_VALUES);
        limits.put("maxCharts", ChartRules.MAX_CHARTS);
        limits.put("maxTitleLength", ChartRules.MAX_TITLE_LENGTH);
        limits.put("schemaVersion", ChartRules.SCHEMA_VERSION);
        return limits;
    }
}
