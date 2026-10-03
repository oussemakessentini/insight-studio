package com.oussamaksantini.insightstudio.chart;

import com.oussamaksantini.insightstudio.chart.ChartDefinition.Filters;
import com.oussamaksantini.insightstudio.chart.ChartEngine.ChartQuery;
import com.oussamaksantini.insightstudio.report.CubeFreshness;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Cube REST queries of the chart builder (services/analytics/model/cubes, docs/chart-builder-api.md).
 * Like the report queries ({@code report.CubeReportQueries}), every query asks for its cube's
 * {@code data_version} and comes in a <b>rollup</b> form (period as the time dimension's
 * {@code dateRange}, served from a daily rollup) and a <b>verified</b> form (period and filters in an
 * OR with the cube's marker rows, so the answer always carries the version; it runs on PostgreSQL).
 *
 * <p>Which cube answers (all exact):
 * <ul>
 *   <li>{@code orders} (one row per order): revenue, orders and units by time or store, or in total,
 *       when the chart has no product or category filter; and a product or category chart's distinct
 *       order total without those filters;</li>
 *   <li>{@code order_categories} (one row per order and category): per-category figures when the chart
 *       has no product filter (its row count is the number of orders containing the category);</li>
 *   <li>{@code order_products} (one row per line item): per-product figures (a product has at most one
 *       line per order, so its row count is the number of orders containing it), from a rollup; and,
 *       with {@code distinct_orders}, every figure that needs distinct orders across several products
 *       or categories (filtered charts), which only the verified form computes.</li>
 * </ul>
 * Values (ids, names, dates) are query values that Cube binds; the business filter duplicates the
 * one Cube adds from the token (services/analytics/security.js).
 */
final class CubeChartQueries {

    static final String ORDERS = "orders";
    static final String ORDER_CATEGORIES = "order_categories";
    static final String ORDER_PRODUCTS = "order_products";

    private CubeChartQueries() {
    }

    /** A cube's member, e.g. {@code member("orders", "revenue")}. */
    static String member(String cube, String name) {
        return cube + "." + name;
    }

    /** The response key of the time dimension at {@code query}'s granularity, e.g. {@code orders.sold_at.week}. */
    static String timeKey(String cube, ChartQuery query) {
        return member(cube, "sold_at") + "." + query.granularity().param();
    }

    /** The grouping dimension of {@code cube} for the query, or {@code null} (time is a time dimension). */
    static String dimension(String cube, ChartQuery query) {
        return switch (query.groupBy()) {
            case NONE, TIME -> null;
            case STORE -> member(cube, "store_id");
            case PRODUCT -> member(cube, "product_id");
            case CATEGORY -> member(cube, "category");
        };
    }

    /**
     * The rollup form: the period as {@code dateRange}, the filters as plain conditions (all are
     * dimensions of the cube's rollup).
     */
    static Map<String, Object> rollup(String cube, List<String> measures, ChartQuery query, boolean grouped) {
        Map<String, Object> timeDimension = new LinkedHashMap<>();
        timeDimension.put("dimension", member(cube, "sold_at"));
        if (grouped && query.granularity() != null) {
            timeDimension.put("granularity", query.granularity().param());
        }
        timeDimension.put("dateRange", List.of(query.period().from().toString(), query.period().to().toString()));
        return query(cube, measures, grouped ? dimension(cube, query) : null, timeDimension,
                itemConditions(cube, query.filters()), query);
    }

    /** The verified form: {@code (period AND filters) OR sold_at is not set (marker rows)}. */
    static Map<String, Object> verified(String cube, List<String> measures, ChartQuery query, boolean grouped) {
        Map<String, Object> timeDimension = null;
        if (grouped && query.granularity() != null) {
            timeDimension = new LinkedHashMap<>();
            timeDimension.put("dimension", member(cube, "sold_at"));
            timeDimension.put("granularity", query.granularity().param());
        }
        List<Object> sales = new ArrayList<>();
        sales.add(Map.of("member", member(cube, "sold_at"), "operator", "inDateRange",
                "values", List.of(query.period().from().toString(), query.period().to().toString())));
        sales.addAll(itemConditions(cube, query.filters()));
        Map<String, Object> markers = Map.of("member", member(cube, "sold_at"), "operator", "notSet");
        Map<String, Object> periodOrMarkers = Map.of("or", List.of(Map.of("and", sales), markers));
        return query(cube, measures, grouped ? dimension(cube, query) : null, timeDimension, List.of(periodOrMarkers),
                query);
    }

    /** Store, product and category conditions (only those the cube has; callers pick the cube). */
    private static List<Map<String, Object>> itemConditions(String cube, Filters filters) {
        List<Map<String, Object>> conditions = new ArrayList<>();
        if (!filters.storeIds().isEmpty()) {
            conditions.add(equalsAny(member(cube, "store_id"), filters.storeIds().stream().map(String::valueOf).toList()));
        }
        if (!filters.productIds().isEmpty()) {
            if (!cube.equals(ORDER_PRODUCTS)) {
                throw new IllegalArgumentException(cube + " has no product");
            }
            conditions.add(equalsAny(member(cube, "product_id"),
                    filters.productIds().stream().map(String::valueOf).toList()));
        }
        if (!filters.categories().isEmpty()) {
            if (cube.equals(ORDERS)) {
                throw new IllegalArgumentException(cube + " has no category");
            }
            conditions.add(equalsAny(member(cube, "category"), filters.categories()));
        }
        return conditions;
    }

    private static Map<String, Object> equalsAny(String member, List<String> values) {
        return Map.of("member", member, "operator", "equals", "values", values);
    }

    private static Map<String, Object> query(
            String cube,
            List<String> measures,
            String dimension,
            Map<String, Object> timeDimension,
            List<? extends Map<String, Object>> filters,
            ChartQuery chart) {
        List<Map<String, Object>> allFilters = new ArrayList<>();
        allFilters.add(Map.of("member", member(cube, "business_id"), "operator", "equals",
                "values", List.of(String.valueOf(chart.businessId()))));
        allFilters.addAll(filters);
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("measures", measures);
        if (dimension != null) {
            query.put("dimensions", List.of(dimension));
        }
        if (timeDimension != null) {
            query.put("timeDimensions", List.of(timeDimension));
        }
        query.put("filters", allFilters);
        query.put("timezone", chart.period().zone().getId());
        query.put("limit", CubeFreshness.ROW_LIMIT);
        return query;
    }
}
