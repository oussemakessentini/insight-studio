package com.oussamaksantini.insightstudio.chart;

import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.chart.ChartEngine.ChartFigures;
import com.oussamaksantini.insightstudio.chart.ChartEngine.Figures;
import com.oussamaksantini.insightstudio.chart.dto.ChartResult;
import com.oussamaksantini.insightstudio.chart.dto.ChartResult.Column;
import com.oussamaksantini.insightstudio.chart.dto.ChartResult.Row;
import com.oussamaksantini.insightstudio.reporting.DateRange;
import com.oussamaksantini.insightstudio.reporting.Granularity;
import com.oussamaksantini.insightstudio.reporting.ReportCalculations;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns an engine's raw figures into a {@link ChartResult}; the same for both engines, so a chart
 * looks the same whichever engine computed it.
 *
 * <ul>
 *   <li><b>Time</b>: every bucket overlapping the period, zero-filled, with partial edge buckets
 *       flagged, exactly like the dashboard revenue series ({@link ReportCalculations#buckets}).</li>
 *   <li><b>Store</b> and <b>category</b>: every store (category of the catalogue) allowed by the
 *       filters, zeros included, like the dashboard's sales by store and the category report.
 *       <b>Product</b>: the products with sales (the catalogue can be large).</li>
 *   <li>Groups are ranked by the first metric (highest first), ties by label ignoring case, then key,
 *       and cut to {@code limit} ({@code truncated}, {@code totalGroups}).</li>
 *   <li>Totals are over the whole filtered period (all groups), with distinct orders.</li>
 * </ul>
 */
@Component
class ChartResults {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter WEEK = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);
    private static final Comparator<String> LABEL_ORDER =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    private final ChartLookups lookups;
    private final Clock clock;

    ChartResults(ChartLookups lookups, Clock clock) {
        this.lookups = lookups;
        this.clock = clock;
    }

    ChartResult build(ChartDefinition definition, Business business, DateRange period, ChartFigures figures, String engine) {
        List<Column> columns = new ArrayList<>();
        if (definition.groupBy() != ChartGroupBy.NONE) {
            columns.add(new Column("group", groupLabel(definition), "dimension", null));
        }
        for (ChartMetric metric : definition.metrics()) {
            columns.add(new Column(metric.key(), metric.label(), "metric", metric.unit().key()));
        }

        List<Row> rows = new ArrayList<>();
        int totalGroups;
        boolean truncated = false;
        switch (definition.groupBy()) {
            case NONE -> {
                rows.add(new Row("total", "Total", values(definition, figures.total()), false));
                totalGroups = 1;
            }
            case TIME -> {
                for (ReportCalculations.Bucket bucket :
                        ReportCalculations.buckets(period.from(), period.to(), definition.granularity())) {
                    String key = bucket.start().toString();
                    rows.add(new Row(key, bucketLabel(bucket.start(), definition.granularity()),
                            values(definition, figures.groups().getOrDefault(key, Figures.ZERO)), !bucket.complete()));
                }
                totalGroups = rows.size();
            }
            default -> {
                Map<String, String> labels = groupLabels(definition, business.getId(), figures);
                List<Row> groups = new ArrayList<>();
                labels.forEach((key, label) -> groups.add(new Row(key, label,
                        values(definition, figures.groups().getOrDefault(key, Figures.ZERO)), false)));
                String first = definition.primaryMetric().key();
                groups.sort(Comparator.<Row, BigDecimal>comparing(row -> decimal(row.values().get(first))).reversed()
                        .thenComparing(Row::label, LABEL_ORDER)
                        .thenComparing(Row::key));
                totalGroups = groups.size();
                truncated = totalGroups > definition.limit();
                rows.addAll(truncated ? groups.subList(0, definition.limit()) : groups);
            }
        }

        return new ChartResult(
                period,
                business.getTimeZone(),
                business.getCurrency(),
                engine,
                definition.groupBy().key(),
                definition.granularity() == null ? null : definition.granularity().param(),
                columns,
                rows,
                values(definition, figures.total()),
                truncated,
                totalGroups,
                Instant.now(clock));
    }

    /** Metric values in the definition's order: money at scale 2, counts as whole numbers. */
    static Map<String, Object> values(ChartDefinition definition, Figures figures) {
        BigDecimal revenue = ReportCalculations.money(figures.revenue());
        Map<String, Object> values = new LinkedHashMap<>();
        for (ChartMetric metric : definition.metrics()) {
            values.put(metric.key(), switch (metric) {
                case REVENUE -> revenue;
                case ORDERS -> figures.orders();
                case UNITS -> figures.units();
                case AVERAGE_ORDER_VALUE -> ReportCalculations.averageOrderValue(revenue, figures.orders());
            });
        }
        return values;
    }

    /**
     * Key -> label of every group to show: the allowed stores or categories (zeros included) or the
     * products with sales, plus any group the figures have that the lookups missed (a store or
     * product created or renamed meanwhile).
     */
    private Map<String, String> groupLabels(ChartDefinition definition, long businessId, ChartFigures figures) {
        ChartDefinition.Filters filters = definition.filters();
        Map<String, String> labels = new LinkedHashMap<>();
        switch (definition.groupBy()) {
            case STORE -> {
                lookups.stores(businessId).forEach((id, name) -> {
                    if (filters.storeIds().isEmpty() || filters.storeIds().contains(id)) {
                        labels.put(Long.toString(id), name);
                    }
                });
                figures.groups().keySet().forEach(key -> labels.putIfAbsent(key, "Store " + key));
            }
            case CATEGORY -> {
                Set<String> allowed = new LinkedHashSet<>(lookups.categories(businessId));
                if (!filters.categories().isEmpty()) {
                    allowed.retainAll(filters.categories());
                }
                if (!filters.productIds().isEmpty()) {
                    allowed.retainAll(lookups.categoriesOf(businessId, filters.productIds()));
                }
                allowed.forEach(name -> labels.put(name, name));
                figures.groups().keySet().forEach(key -> labels.putIfAbsent(key, key));
            }
            case PRODUCT -> {
                List<Long> ids = figures.groups().keySet().stream().map(Long::valueOf).toList();
                Map<Long, String> names = lookups.productNames(businessId, ids);
                ids.forEach(id -> labels.put(Long.toString(id), names.getOrDefault(id, "Product " + id)));
            }
            default -> throw new IllegalArgumentException("Not a ranked grouping: " + definition.groupBy());
        }
        return labels;
    }

    private static String groupLabel(ChartDefinition definition) {
        if (definition.groupBy() != ChartGroupBy.TIME) {
            return definition.groupBy().label();
        }
        return switch (definition.granularity()) {
            case DAY -> "Day";
            case WEEK -> "Week";
            case MONTH -> "Month";
        };
    }

    /** As the dashboard labels buckets: "Mon, Jul 6, 2026", "Week of Jul 6, 2026", "July 2026". */
    static String bucketLabel(LocalDate start, Granularity granularity) {
        return switch (granularity) {
            case DAY -> DAY.format(start);
            case WEEK -> "Week of " + WEEK.format(start);
            case MONTH -> MONTH.format(start);
        };
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : BigDecimal.valueOf(((Number) value).longValue());
    }
}
