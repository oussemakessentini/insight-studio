package com.oussamaksantini.insightstudio.chart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.oussamaksantini.insightstudio.analytics.CubeClient;
import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.chart.dto.ChartCatalogResponse;
import com.oussamaksantini.insightstudio.common.web.FieldErrorsException;
import com.oussamaksantini.insightstudio.common.web.FieldErrorsException.FieldError;
import com.oussamaksantini.insightstudio.report.ReportProperties;
import com.oussamaksantini.insightstudio.savedreport.PeriodResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link ChartValidator} without a database: every combination of visualization, grouping and metrics
 * is accepted exactly when the published catalogue says so (the catalogue and the validator share one
 * set of rules), and every field rule of docs/chart-builder-contract.md §1–§3 answers with its field.
 * Business-specific filter checks (ids of another business) are in {@code ChartApiIntegrationTest}.
 */
class ChartValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Business 1 has store 3, product 7 and category "Tops"; nothing else exists. */
    private static final ChartLookups LOOKUPS = new ChartLookups(null) {
        @Override
        Map<Long, String> stores(long businessId) {
            return Map.of(3L, "Boston");
        }

        @Override
        List<String> categories(long businessId) {
            return List.of("Tops");
        }

        @Override
        Set<Long> existingStores(long businessId, Collection<Long> ids) {
            return ids.contains(3L) ? Set.of(3L) : Set.of();
        }

        @Override
        Set<Long> existingProducts(long businessId, Collection<Long> ids) {
            return ids.contains(7L) ? Set.of(7L) : Set.of();
        }

        @Override
        Set<String> existingCategories(long businessId, Collection<String> names) {
            return names.contains("Tops") ? Set.of("Tops") : Set.of();
        }
    };

    private final ChartEngines sqlOnly = new ChartEngines(null, new ChartProperties(Duration.ofSeconds(10)),
            new ReportProperties(ReportProperties.Engine.SQL, Duration.ofSeconds(10)),
            new StaticListableBeanFactory().getBeanProvider(CubeClient.class));
    /** Today is 2026-10-02 in UTC. */
    private final PeriodResolver periods = new PeriodResolver(Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC));
    private final ChartValidator validator = new ChartValidator(LOOKUPS, sqlOnly, periods);
    private final Business business = business();

    private static Business business() {
        Business business = new Business("Test Co", "test-co", "EUR", "Europe/Paris");
        ReflectionTestUtils.setField(business, "id", 1L);
        return business;
    }

    /** A valid bar chart of revenue by store; tests change one thing at a time. */
    private static ObjectNode valid() {
        return (ObjectNode) JSON.readTree("""
                {"schemaVersion": 1, "title": "Revenue by store", "visualization": "bar", "metrics": ["revenue"],
                 "groupBy": "store", "granularity": null, "range": {"type": "relative", "preset": "last_90_days"},
                 "filters": {"storeIds": [], "categories": [], "productIds": []}, "limit": 10, "engine": "sql"}
                """);
    }

    private static ObjectNode with(Consumer<ObjectNode> change) {
        ObjectNode definition = valid();
        change.accept(definition);
        return definition;
    }

    private List<FieldError> errors(JsonNode definition) {
        FieldErrorsException e = catchThrowableOfType(FieldErrorsException.class,
                () -> validator.validate(definition, business, true));
        assertThat(e).as("refused: %s", definition).isNotNull();
        return e.getErrors();
    }

    private void assertRefused(JsonNode definition, String field, String messagePart) {
        assertThat(errors(definition)).as(definition.toString())
                .anySatisfy(error -> {
                    assertThat(error.field()).isEqualTo(field);
                    assertThat(error.message()).contains(messagePart);
                });
    }

    // ---------------------------------------------------------------- catalogue = validator

    @Test
    void everyCombinationIsAcceptedExactlyWhenTheCatalogueAllowsIt() {
        ChartCatalogResponse catalog = new ChartCatalog(LOOKUPS, sqlOnly).catalog(1);
        List<List<String>> metricSets = metricSets(catalog.metrics().stream().map(ChartCatalogResponse.Metric::key).toList());
        int accepted = 0;
        int refused = 0;
        for (ChartCatalogResponse.Visualization visualization : catalog.visualizations()) {
            for (ChartCatalogResponse.Dimension dimension : catalog.dimensions()) {
                for (List<String> metrics : metricSets) {
                    boolean allowed = visualization.groupBy().contains(dimension.key())
                            && metrics.size() >= visualization.minMetrics() && metrics.size() <= visualization.maxMetrics()
                            && metrics.stream().noneMatch(metric -> catalog.rules().stream().anyMatch(rule ->
                                    (rule.visualization() == null || rule.visualization().equals(visualization.key()))
                                            && (rule.groupBy() == null || rule.groupBy().equals(dimension.key()))
                                            && (rule.metric() == null || rule.metric().equals(metric))));
                    ObjectNode definition = with(d -> {
                        d.put("visualization", visualization.key());
                        d.put("groupBy", dimension.key());
                        ArrayNode list = d.putArray("metrics");
                        metrics.forEach(list::add);
                        if (dimension.key().equals("time")) {
                            d.put("granularity", "week");
                        }
                        if (!List.of("store", "product", "category").contains(dimension.key())) {
                            d.putNull("limit");
                        }
                    });
                    if (allowed) {
                        ChartDefinition parsed = validator.validate(definition, business, true);
                        assertThat(parsed.metrics().stream().map(ChartMetric::key).toList()).isEqualTo(metrics);
                        accepted++;
                    } else {
                        assertThat(errors(definition)).as(definition.toString())
                                .allSatisfy(error -> assertThat(error.field()).matches("metrics(\\[\\d])?|groupBy"));
                        refused++;
                    }
                }
            }
        }
        assertThat(accepted).isGreaterThan(50);
        assertThat(refused).isGreaterThan(accepted);
    }

    /** Every non-empty ordered selection of up to 4 metrics (orders matter: the first one ranks). */
    private static List<List<String>> metricSets(List<String> keys) {
        List<List<String>> sets = new ArrayList<>();
        collect(keys, new ArrayList<>(), sets);
        return sets;
    }

    private static void collect(List<String> keys, List<String> current, List<List<String>> sets) {
        if (!current.isEmpty()) {
            sets.add(List.copyOf(current));
        }
        for (String key : keys) {
            if (!current.contains(key)) {
                current.add(key);
                collect(keys, current, sets);
                current.removeLast();
            }
        }
    }

    @Test
    void theCataloguePublishesTheContractsRules() {
        ChartCatalogResponse catalog = new ChartCatalog(LOOKUPS, sqlOnly).catalog(1);
        assertThat(catalog.engines()).containsExactly("sql");
        assertThat(catalog.defaultEngine()).isEqualTo("sql");
        assertThat(catalog.visualizations()).extracting(ChartCatalogResponse.Visualization::key)
                .containsExactly("kpi", "line", "bar", "pie", "table");
        assertThat(catalog.rules()).extracting(r -> r.visualization() + "/" + r.groupBy() + "/" + r.metric())
                .containsExactlyInAnyOrder("null/product/average_order_value", "null/category/average_order_value",
                        "pie/null/average_order_value", "pie/product/orders", "pie/category/orders");
        assertThat(catalog.rules()).allSatisfy(rule -> assertThat(rule.allowed()).isFalse());
        assertThat(catalog.limits()).containsEntry("maxRangeDays", 1098).containsEntry("maxTimeBuckets", 400)
                .containsEntry("maxLimit", 50).containsEntry("maxCharts", 200);
        assertThat(catalog.filters()).extracting(ChartCatalogResponse.Filter::key)
                .containsExactly("storeIds", "categories", "productIds");
        assertThat(catalog.filters().getFirst().options()).containsExactly(new ChartCatalogResponse.Option(3L, "Boston"));
    }

    // ---------------------------------------------------------------- fields

    @Test
    void normalizesAValidDefinition() {
        ChartDefinition definition = validator.validate(with(d -> {
            d.remove("schemaVersion");
            d.remove("limit");
            d.remove("engine");
            d.remove("filters");
            d.put("title", "  Padded  ");
        }), business, true);
        assertThat(definition.title()).isEqualTo("Padded");
        assertThat(definition.limit()).isEqualTo(10);
        assertThat(definition.engine()).isEqualTo(ChartDefinition.Engine.SQL);
        assertThat(definition.toJson()).isEqualTo(JSON.readTree("""
                {"schemaVersion": 1, "title": "Padded", "visualization": "bar", "metrics": ["revenue"],
                 "groupBy": "store", "granularity": null, "range": {"type": "relative", "preset": "last_90_days"},
                 "filters": {"storeIds": [], "categories": [], "productIds": []}, "limit": 10, "engine": "sql"}
                """));
    }

    @Test
    void refusesEveryBrokenRuleWithItsField() {
        assertRefused(with(d -> d.put("schemaVersion", 2)), "schemaVersion", "must be 1");
        assertRefused(with(d -> d.remove("title")), "title", "Enter a title");
        assertRefused(with(d -> d.put("title", "   ")), "title", "Enter a title");
        assertRefused(with(d -> d.put("title", "x".repeat(121))), "title", "at most 120");
        assertRefused(with(d -> d.put("title", "bad\u0007")), "title", "invalid characters");
        assertRefused(with(d -> d.put("visualization", "radar")), "visualization", "kpi, line, bar, pie, table");
        assertRefused(with(d -> d.putArray("metrics")), "metrics", "at least one metric");
        assertRefused(with(d -> d.putArray("metrics").add("profit")), "metrics[0]", "revenue, orders, units");
        assertRefused(with(d -> d.putArray("metrics").add("revenue").add("revenue")), "metrics[1]", "listed twice");
        assertRefused(with(d -> d.putArray("metrics").add("revenue").add("units")), "metrics", "exactly 1 metric");
        assertRefused(with(d -> {
            d.put("visualization", "kpi");
            d.put("groupBy", "none");
            d.putNull("limit");
            d.putArray("metrics").add("revenue").add("orders").add("units").add("average_order_value").add("x");
        }), "metrics[4]", "must be one of");
        assertRefused(with(d -> d.put("groupBy", "region")), "groupBy", "none, time, store, product, category");
        assertRefused(with(d -> d.put("visualization", "line")), "groupBy", "groupBy must be time");
        assertRefused(with(d -> {
            d.put("visualization", "kpi");
            d.putNull("limit");
        }), "groupBy", "groupBy must be none");
        assertRefused(with(d -> d.put("granularity", "month")), "granularity", "only used when grouping by time");
        assertRefused(with(d -> {
            d.put("groupBy", "time");
            d.putNull("limit");
        }), "granularity", "day, week or month");
        assertRefused(with(d -> {
            d.put("groupBy", "time");
            d.put("granularity", "hour");
            d.putNull("limit");
        }), "granularity", "day, week or month");
        assertRefused(with(d -> d.put("limit", 0)), "limit", "from 1 to 50");
        assertRefused(with(d -> d.put("limit", 51)), "limit", "from 1 to 50");
        assertRefused(with(d -> d.put("limit", 2.5)), "limit", "from 1 to 50");
        assertRefused(with(d -> {
            d.put("visualization", "table");
            d.put("groupBy", "none");
        }), "limit", "only used when grouping by store, product or category");
        assertRefused(with(d -> d.put("engine", "spark")), "engine", "sql or cube");
        assertRefused(with(d -> d.put("engine", "cube")), "engine", "Cube is not configured");
        assertRefused(with(d -> d.putObject("layout").put("x", 1)), "layout", "Unknown field 'layout'");
        assertRefused(with(d -> d.put("dashboardId", 4)), "dashboardId", "Unknown field");
    }

    @Test
    void refusesInvalidRanges() {
        assertRefused(with(d -> d.remove("range")), "range", "'range' is required");
        assertRefused(with(d -> d.putObject("range").put("type", "rolling")), "range.type", "fixed or relative");
        assertRefused(with(d -> d.putObject("range").put("type", "relative").put("preset", "last_week")),
                "range.preset", "last_7_days");
        assertRefused(with(d -> d.putObject("range").put("type", "fixed").put("to", "2026-07-01")),
                "range.from", "required for a fixed range");
        assertRefused(with(d -> d.putObject("range").put("type", "fixed").put("from", "2026-07-01").put("to", "07/02/2026")),
                "range.to", "a date such as 2026-07-01");
        assertRefused(with(d -> d.putObject("range").put("type", "fixed").put("from", "2026-07-02").put("to", "2026-07-01")),
                "range", "must be on or before");
        assertRefused(with(d -> d.putObject("range").put("type", "fixed").put("from", "2023-01-01").put("to", "2026-01-03")),
                "range", "at most 1098 days");
        assertRefused(with(d -> d.putObject("range").put("type", "fixed").put("from", "2026-01-01").put("to", "2026-01-31")
                .put("extra", true)), "range.extra", "Unknown field");
        // 1,098 days is the longest range (monthly or weekly buckets).
        for (String granularity : List.of("week", "month")) {
            validator.validate(with(d -> {
                d.put("visualization", "line");
                d.put("groupBy", "time");
                d.put("granularity", granularity);
                d.putNull("limit");
                d.putObject("range").put("type", "fixed").put("from", "2024-01-01").put("to", "2027-01-02");
            }), business, true);
        }
    }

    @Test
    void dailyBucketsCoverAtMost366Days() {
        Consumer<ObjectNode> daily = d -> {
            d.put("visualization", "line");
            d.put("groupBy", "time");
            d.put("granularity", "day");
            d.putNull("limit");
        };
        validator.validate(with(daily.andThen(d -> d.putObject("range").put("type", "fixed")
                .put("from", "2024-01-01").put("to", "2024-12-31"))), business, true); // 366 days (leap year)
        assertRefused(with(daily.andThen(d -> d.putObject("range").put("type", "fixed")
                .put("from", "2025-01-01").put("to", "2026-01-02"))), "granularity", "Daily buckets cover at most 366 days");
        // Every preset fits daily buckets (the longest is 366 days).
        for (String preset : List.of("last_365_days", "last_12_months", "previous_year", "year_to_date")) {
            validator.validate(with(daily.andThen(d -> d.putObject("range").put("type", "relative").put("preset", preset))),
                    business, true);
        }
    }

    @Test
    void refusesInvalidFilters() {
        assertRefused(with(d -> d.put("filters", "all")), "filters", "must be an object");
        assertRefused(with(d -> d.putObject("filters").put("storeIds", 3)), "filters.storeIds", "list of store ids");
        assertRefused(with(d -> d.putObject("filters").putArray("storeIds").add("3")), "filters.storeIds[0]", "store id");
        assertRefused(with(d -> d.putObject("filters").putArray("storeIds").add(-3)), "filters.storeIds[0]", "store id");
        assertRefused(with(d -> d.putObject("filters").putArray("storeIds").add(3).add(3)), "filters.storeIds[1]",
                "Store 3 is listed twice");
        assertRefused(with(d -> {
            ArrayNode ids = d.putObject("filters").putArray("productIds");
            for (int i = 1; i <= 51; i++) {
                ids.add(i);
            }
        }), "filters.productIds", "At most 50 products");
        assertRefused(with(d -> d.putObject("filters").putArray("categories").add(" ")), "filters.categories[0]",
                "category name");
        assertRefused(with(d -> d.putObject("filters").put("regions", true)), "filters.regions", "Unknown field");
        // Ids and names that are not the business's own.
        assertRefused(with(d -> d.putObject("filters").putArray("storeIds").add(3).add(12)), "filters.storeIds[1]",
                "Store 12 is not a store of this business.");
        assertRefused(with(d -> d.putObject("filters").putArray("productIds").add(99)), "filters.productIds[0]",
                "Product 99 is not a product of this business.");
        assertRefused(with(d -> d.putObject("filters").putArray("categories").add("tops")), "filters.categories[0]",
                "'tops' is not a category of this business.");
        ChartDefinition filtered = validator.validate(with(d -> {
            ObjectNode filters = d.putObject("filters");
            filters.putArray("storeIds").add(3);
            filters.putArray("categories").add("Tops");
            filters.putArray("productIds").add(7);
        }), business, true);
        assertThat(filtered.filters()).isEqualTo(new ChartDefinition.Filters(List.of(3L), List.of("Tops"), List.of(7L)));
    }

    @Test
    void reportsEveryErrorAtOnceAndAPreviewNeedsNoTitle() {
        List<FieldError> errors = errors(with(d -> {
            d.remove("title");
            d.put("visualization", "pie");
            d.putArray("metrics").add("average_order_value");
            d.put("limit", 99);
        }));
        assertThat(errors).extracting(FieldError::field).containsExactly("title", "limit", "metrics[0]");
        assertThat(errors.get(2).message()).isEqualTo(ChartRules.PIE_AVERAGE);

        validator.validate(with(d -> d.remove("title")), business, false);
        assertThatThrownBy(() -> validator.validate(JSON.readTree("[1]"), business, false))
                .isInstanceOf(FieldErrorsException.class)
                .hasMessage("The chart definition must be a JSON object.");
    }
}
