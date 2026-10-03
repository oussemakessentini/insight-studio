package com.oussamaksantini.insightstudio.chart;

import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.chart.ChartDefinition.Engine;
import com.oussamaksantini.insightstudio.chart.ChartDefinition.Filters;
import com.oussamaksantini.insightstudio.common.web.FieldErrorsException;
import com.oussamaksantini.insightstudio.common.web.FieldErrorsException.FieldError;
import com.oussamaksantini.insightstudio.reporting.DateRange;
import com.oussamaksantini.insightstudio.reporting.Granularity;
import com.oussamaksantini.insightstudio.reporting.ReportCalculations;
import com.oussamaksantini.insightstudio.savedreport.PeriodResolver;
import com.oussamaksantini.insightstudio.savedreport.RelativePreset;
import com.oussamaksantini.insightstudio.savedreport.SavedRange;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Checks a chart definition sent as JSON against every rule of docs/chart-builder-contract.md §1–§3
 * ({@link ChartRules}, {@link ChartVisualization}) and against the business: filter ids and
 * categories must be the business's own, and {@code cube} must be offered. All problems are reported
 * at once, each with its field as a JSON path ({@code metrics[1]}, {@code range.from},
 * {@code filters.storeIds[0]}), as a 400 {@link FieldErrorsException}.
 *
 * <p>Fields the contract does not define are refused, so a definition never carries anything else
 * (such as a dashboard layout).
 */
@Component
class ChartValidator {

    private static final Set<String> FIELDS = Set.of("schemaVersion", "title", "visualization", "metrics", "groupBy",
            "granularity", "range", "filters", "limit", "engine");
    private static final Set<String> RANGE_FIELDS = Set.of("type", "preset", "from", "to");
    private static final Set<String> FILTER_FIELDS = Set.of("storeIds", "categories", "productIds");
    /** Longest category name ({@code products.category}). */
    private static final int MAX_CATEGORY_LENGTH = 100;

    private final ChartLookups lookups;
    private final ChartEngines engines;
    private final PeriodResolver periods;

    ChartValidator(ChartLookups lookups, ChartEngines engines, PeriodResolver periods) {
        this.lookups = lookups;
        this.engines = engines;
        this.periods = periods;
    }

    /**
     * The definition, normalized (defaults filled in, title trimmed).
     *
     * @param requireTitle {@code false} for previews of unsaved definitions, which may have no title yet
     * @throws FieldErrorsException 400 listing every invalid field
     */
    ChartDefinition validate(JsonNode body, Business business, boolean requireTitle) {
        if (body == null || !body.isObject()) {
            throw new FieldErrorsException(List.of(new FieldError("definition", "The chart definition must be a JSON object.")));
        }
        Errors errors = new Errors();
        for (String name : names(body)) {
            if (!FIELDS.contains(name)) {
                errors.add(name, "Unknown field '%s'.".formatted(name));
            }
        }

        JsonNode schemaVersion = body.get("schemaVersion");
        if (present(schemaVersion) && !(schemaVersion.isIntegralNumber() && schemaVersion.asLong() == ChartRules.SCHEMA_VERSION)) {
            errors.add("schemaVersion", "'schemaVersion' must be %d.".formatted(ChartRules.SCHEMA_VERSION));
        }
        String title = title(body.get("title"), requireTitle, errors);
        ChartVisualization visualization = code(body.get("visualization"), "visualization",
                ChartVisualization::fromKey, ChartVisualization.values(), ChartVisualization::key, errors);
        List<ChartMetric> metrics = metrics(body.get("metrics"), errors);
        ChartGroupBy groupBy = code(body.get("groupBy"), "groupBy", ChartGroupBy::fromKey, ChartGroupBy.values(),
                ChartGroupBy::key, errors);
        Granularity granularity = granularity(body.get("granularity"), groupBy, errors);
        SavedRange range = range(body.get("range"), errors);
        Filters filters = filters(body.get("filters"), errors);
        Integer limit = limit(body.get("limit"), groupBy, errors);
        Engine engine = engine(body.get("engine"), errors);

        if (visualization != null && metrics != null) {
            if (metrics.size() < visualization.minMetrics() || metrics.size() > visualization.maxMetrics()) {
                errors.add("metrics", "%s: choose %s.".formatted(visualization.label(),
                        visualization.minMetrics() == visualization.maxMetrics()
                                ? "exactly %d metric".formatted(visualization.minMetrics())
                                : "%d to %d metrics".formatted(visualization.minMetrics(), visualization.maxMetrics())));
            }
        }
        if (visualization != null && groupBy != null && !visualization.groupBy().contains(groupBy)) {
            errors.add("groupBy", "%s: groupBy must be %s.".formatted(visualization.label(),
                    or(visualization.groupBy().stream().map(ChartGroupBy::key).toList())));
        }
        if (visualization != null && groupBy != null && metrics != null) {
            for (int i = 0; i < metrics.size(); i++) {
                ChartRules.Denial denial = ChartRules.denial(visualization, groupBy, metrics.get(i));
                if (denial != null) {
                    errors.add("metrics[%d]".formatted(i), denial.reason());
                }
            }
        }
        if (range != null && granularity != null) {
            DateRange period = periods.resolve(range, business.zoneId());
            long days = ChronoUnit.DAYS.between(period.from(), period.to()) + 1;
            int buckets = ReportCalculations.bucketStarts(period.from(), period.to(), granularity).size();
            if (days > ChartRules.maxRangeDays(granularity)) {
                errors.add("granularity", "%s buckets cover at most %d days: choose larger buckets or a shorter period."
                        .formatted(granularity == Granularity.DAY ? "Daily" : "Weekly", ChartRules.maxRangeDays(granularity)));
            } else if (buckets > ChartRules.MAX_TIME_BUCKETS) {
                errors.add("granularity", "The chart would have %d time buckets; at most %d are allowed."
                        .formatted(buckets, ChartRules.MAX_TIME_BUCKETS));
            }
        }
        if (filters != null) {
            checkFiltersBelongToBusiness(filters, business.getId(), errors);
        }
        if (engine != null && !engines.offers(engine)) {
            errors.add("engine", "Cube is not configured on this server; use the sql engine.");
        }

        errors.throwIfAny();
        return new ChartDefinition(ChartRules.SCHEMA_VERSION, title, visualization, List.copyOf(metrics), groupBy,
                granularity, range, filters, limit, engine);
    }

    // ---------------------------------------------------------------- fields

    private static String title(JsonNode node, boolean required, Errors errors) {
        if (!present(node)) {
            if (required) {
                errors.add("title", "Enter a title for the chart.");
            }
            return "";
        }
        if (!node.isString()) {
            errors.add("title", "'title' must be text.");
            return null;
        }
        String title = node.asString().strip();
        if (title.isEmpty() && required) {
            errors.add("title", "Enter a title for the chart.");
        } else if (title.length() > ChartRules.MAX_TITLE_LENGTH) {
            errors.add("title", "The title may be at most %d characters.".formatted(ChartRules.MAX_TITLE_LENGTH));
        } else if (title.chars().anyMatch(Character::isISOControl)) {
            errors.add("title", "The title contains invalid characters.");
        }
        return title;
    }

    private static <T> T code(JsonNode node, String field, Function<String, java.util.Optional<T>> fromKey, T[] values,
            Function<T, String> key, Errors errors) {
        T value = node != null && node.isString() ? fromKey.apply(node.asString()).orElse(null) : null;
        if (value == null) {
            errors.add(field, "'%s' must be one of %s.".formatted(field,
                    Arrays.stream(values).map(key).collect(Collectors.joining(", "))));
        }
        return value;
    }

    private static List<ChartMetric> metrics(JsonNode node, Errors errors) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            errors.add("metrics", "Choose at least one metric.");
            return null;
        }
        List<ChartMetric> metrics = new ArrayList<>();
        boolean valid = true;
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            ChartMetric metric = item.isString() ? ChartMetric.fromKey(item.asString()).orElse(null) : null;
            String field = "metrics[%d]".formatted(i);
            if (metric == null) {
                errors.add(field, "'%s' must be one of %s.".formatted(field,
                        Arrays.stream(ChartMetric.values()).map(ChartMetric::key).collect(Collectors.joining(", "))));
                valid = false;
            } else if (metrics.contains(metric)) {
                errors.add(field, "'%s' is listed twice.".formatted(metric.key()));
                valid = false;
            } else {
                metrics.add(metric);
            }
        }
        return valid ? metrics : null;
    }

    private static Granularity granularity(JsonNode node, ChartGroupBy groupBy, Errors errors) {
        if (groupBy != ChartGroupBy.TIME) {
            if (present(node)) {
                errors.add("granularity", "'granularity' is only used when grouping by time; set it to null.");
            }
            return null;
        }
        Granularity granularity = null;
        if (node != null && node.isString()) {
            granularity = Arrays.stream(Granularity.values()).filter(g -> g.param().equals(node.asString()))
                    .findFirst().orElse(null);
        }
        if (granularity == null) {
            errors.add("granularity", "'granularity' must be day, week or month when grouping by time.");
        }
        return granularity;
    }

    private static SavedRange range(JsonNode node, Errors errors) {
        if (node == null || !node.isObject()) {
            errors.add("range", "'range' is required: {\"type\": \"fixed\", \"from\", \"to\"} or "
                    + "{\"type\": \"relative\", \"preset\"}.");
            return null;
        }
        for (String name : names(node)) {
            if (!RANGE_FIELDS.contains(name)) {
                errors.add("range." + name, "Unknown field 'range.%s'.".formatted(name));
            }
        }
        JsonNode type = node.get("type");
        String typeCode = type != null && type.isString() ? type.asString() : null;
        if (SavedRange.RELATIVE.equals(typeCode)) {
            JsonNode preset = node.get("preset");
            RelativePreset value = preset != null && preset.isString()
                    ? RelativePreset.fromCode(preset.asString()).orElse(null)
                    : null;
            if (value == null) {
                errors.add("range.preset", "'range.preset' must be one of %s.".formatted(
                        Arrays.stream(RelativePreset.values()).map(RelativePreset::code).collect(Collectors.joining(", "))));
                return null;
            }
            return SavedRange.relative(value);
        }
        if (!SavedRange.FIXED.equals(typeCode)) {
            errors.add("range.type", "'range.type' must be fixed or relative.");
            return null;
        }
        LocalDate from = date(node.get("from"), "range.from", errors);
        LocalDate to = date(node.get("to"), "range.to", errors);
        if (from == null || to == null) {
            return null;
        }
        // The same rules (and messages) as the report API and saved reports.
        if (from.isAfter(to)) {
            errors.add("range", "'from' (%s) must be on or before 'to' (%s).".formatted(from, to));
            return null;
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > ChartRules.MAX_RANGE_DAYS) {
            errors.add("range", "The date range may cover at most %d days.".formatted(ChartRules.MAX_RANGE_DAYS));
            return null;
        }
        return SavedRange.fixed(from, to);
    }

    private static LocalDate date(JsonNode node, String field, Errors errors) {
        if (!present(node)) {
            errors.add(field, "'%s' is required for a fixed range.".formatted(field));
            return null;
        }
        try {
            if (node.isString()) {
                return LocalDate.parse(node.asString().strip());
            }
        } catch (DateTimeParseException e) {
            // reported below
        }
        errors.add(field, "'%s' must be a date such as 2026-07-01.".formatted(field));
        return null;
    }

    private static Filters filters(JsonNode node, Errors errors) {
        if (!present(node)) {
            return Filters.NONE;
        }
        if (!node.isObject()) {
            errors.add("filters", "'filters' must be an object with storeIds, categories and productIds.");
            return null;
        }
        for (String name : names(node)) {
            if (!FILTER_FIELDS.contains(name)) {
                errors.add("filters." + name, "Unknown field 'filters.%s'.".formatted(name));
            }
        }
        List<Long> storeIds = ids(node.get("storeIds"), "filters.storeIds", "store", errors);
        List<String> categories = categories(node.get("categories"), errors);
        List<Long> productIds = ids(node.get("productIds"), "filters.productIds", "product", errors);
        if (storeIds == null || categories == null || productIds == null) {
            return null;
        }
        return new Filters(storeIds, categories, productIds);
    }

    private static List<Long> ids(JsonNode node, String field, String what, Errors errors) {
        if (!present(node)) {
            return List.of();
        }
        if (!node.isArray()) {
            errors.add(field, "'%s' must be a list of %s ids.".formatted(field, what));
            return null;
        }
        if (node.size() > ChartRules.MAX_FILTER_VALUES) {
            errors.add(field, "At most %d %ss can be selected.".formatted(ChartRules.MAX_FILTER_VALUES, what));
            return null;
        }
        Set<Long> ids = new LinkedHashSet<>();
        boolean valid = true;
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            String itemField = "%s[%d]".formatted(field, i);
            if (!item.isIntegralNumber() || !item.canConvertToLong() || item.asLong() <= 0) {
                errors.add(itemField, "'%s' must be a %s id.".formatted(itemField, what));
                valid = false;
            } else if (!ids.add(item.asLong())) {
                errors.add(itemField, "%s %d is listed twice.".formatted(capitalize(what), item.asLong()));
                valid = false;
            }
        }
        return valid ? List.copyOf(ids) : null;
    }

    private static List<String> categories(JsonNode node, Errors errors) {
        String field = "filters.categories";
        if (!present(node)) {
            return List.of();
        }
        if (!node.isArray()) {
            errors.add(field, "'%s' must be a list of category names.".formatted(field));
            return null;
        }
        if (node.size() > ChartRules.MAX_FILTER_VALUES) {
            errors.add(field, "At most %d categories can be selected.".formatted(ChartRules.MAX_FILTER_VALUES));
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        boolean valid = true;
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            String itemField = "%s[%d]".formatted(field, i);
            String name = item.isString() ? item.asString() : null;
            if (name == null || name.isBlank() || name.length() > MAX_CATEGORY_LENGTH) {
                errors.add(itemField, "'%s' must be a category name.".formatted(itemField));
                valid = false;
            } else if (!names.add(name)) {
                errors.add(itemField, "Category '%s' is listed twice.".formatted(name));
                valid = false;
            }
        }
        return valid ? List.copyOf(names) : null;
    }

    private static Integer limit(JsonNode node, ChartGroupBy groupBy, Errors errors) {
        if (groupBy == null) {
            return null;
        }
        if (!groupBy.ranked()) {
            if (present(node)) {
                errors.add("limit", "'limit' is only used when grouping by store, product or category; set it to null.");
            }
            return null;
        }
        if (!present(node)) {
            return ChartRules.DEFAULT_LIMIT;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()
                || node.asInt() < ChartRules.MIN_LIMIT || node.asInt() > ChartRules.MAX_LIMIT) {
            errors.add("limit", "'limit' must be a whole number from %d to %d."
                    .formatted(ChartRules.MIN_LIMIT, ChartRules.MAX_LIMIT));
            return null;
        }
        return node.asInt();
    }

    private static Engine engine(JsonNode node, Errors errors) {
        if (!present(node)) {
            return Engine.SQL;
        }
        Engine engine = node.isString() ? Engine.fromKey(node.asString()).orElse(null) : null;
        if (engine == null) {
            errors.add("engine", "'engine' must be sql or cube.");
        }
        return engine;
    }

    /** Store and product ids and category names must be the business's own (never confirming others'). */
    private void checkFiltersBelongToBusiness(Filters filters, long businessId, Errors errors) {
        Set<Long> stores = lookups.existingStores(businessId, filters.storeIds());
        for (int i = 0; i < filters.storeIds().size(); i++) {
            long id = filters.storeIds().get(i);
            if (!stores.contains(id)) {
                errors.add("filters.storeIds[%d]".formatted(i), "Store %d is not a store of this business.".formatted(id));
            }
        }
        Set<String> categories = lookups.existingCategories(businessId, filters.categories());
        for (int i = 0; i < filters.categories().size(); i++) {
            String name = filters.categories().get(i);
            if (!categories.contains(name)) {
                errors.add("filters.categories[%d]".formatted(i),
                        "'%s' is not a category of this business.".formatted(name));
            }
        }
        Set<Long> products = lookups.existingProducts(businessId, filters.productIds());
        for (int i = 0; i < filters.productIds().size(); i++) {
            long id = filters.productIds().get(i);
            if (!products.contains(id)) {
                errors.add("filters.productIds[%d]".formatted(i),
                        "Product %d is not a product of this business.".formatted(id));
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A value other than missing or JSON {@code null}. */
    private static boolean present(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode();
    }

    private static List<String> names(JsonNode object) {
        return object.properties().stream().map(Map.Entry::getKey).toList();
    }

    private static String or(List<String> values) {
        return values.size() == 1
                ? values.getFirst()
                : String.join(", ", values.subList(0, values.size() - 1)) + " or " + values.getLast();
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Collected field errors, in the order found. */
    private static final class Errors {

        private final List<FieldError> list = new ArrayList<>();

        void add(String field, String message) {
            list.add(new FieldError(field, message));
        }

        void throwIfAny() {
            if (!list.isEmpty()) {
                throw new FieldErrorsException(list);
            }
        }
    }
}
