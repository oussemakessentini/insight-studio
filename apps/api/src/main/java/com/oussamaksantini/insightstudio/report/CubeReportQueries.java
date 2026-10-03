package com.oussamaksantini.insightstudio.report;

import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Cube REST queries behind the reports (services/analytics/model/cubes, docs/cube-reports.md).
 *
 * <p>Every query asks for the cube's {@code data_version} next to the figures: the
 * {@code report_data_version} that the rows were read at. Each kind of query comes in two forms:
 * <ul>
 *   <li>the <b>rollup</b> form filters the period with the time dimension's {@code dateRange}, which
 *       Cube serves from the daily rollups; every row it returns carries the version of the build it
 *       came from, but a period without sales returns no row and therefore no version;</li>
 *   <li>the <b>verified</b> form puts the period (and store) in an OR with the cube's marker rows
 *       ({@code sold_at} not set), so the answer always contains a row with the version, even when
 *       the period has no sales. Cube cannot serve a filter on the time dimension from a rollup, so
 *       this form runs as one statement on PostgreSQL (rows and version from the same snapshot).</li>
 * </ul>
 * Dates are the filter's inclusive local dates and the query {@code timezone} is the business's, so
 * Cube buckets days and months in that zone. The explicit business filter duplicates the one Cube
 * adds from the token (services/analytics/security.js).
 */
final class CubeReportQueries {

    static final String ORDERS_MONTH = "orders.sold_at.month";
    static final String ORDERS_REVENUE = "orders.revenue";
    static final String ORDERS_COUNT = "orders.count";
    static final String ORDERS_UNITS = "orders.units";
    static final String ORDERS_VERSION = "orders.data_version";
    static final String CATEGORY = "order_categories.category";
    static final String CATEGORY_REVENUE = "order_categories.revenue";
    static final String CATEGORY_UNITS = "order_categories.units";
    static final String CATEGORY_ORDERS = "order_categories.count";
    static final String CATEGORY_VERSION = "order_categories.data_version";

    /** Row limit sent with every query; an answer with this many rows is treated as invalid. */
    static final int ROW_LIMIT = CubeFreshness.ROW_LIMIT;

    private static final String ORDERS_SOLD_AT = "orders.sold_at";
    private static final String CATEGORY_SOLD_AT = "order_categories.sold_at";

    private CubeReportQueries() {
    }

    /** Revenue, orders and units per local month (rollup {@code orders.daily_by_store}). */
    static Map<String, Object> monthly(ReportFilter filter) {
        return query(filter, "orders",
                List.of(ORDERS_REVENUE, ORDERS_COUNT, ORDERS_UNITS, ORDERS_VERSION), List.of(),
                timeDimension(ORDERS_SOLD_AT, "month", filter), periodFilters(filter, "orders"));
    }

    /** {@link #monthly} plus the business's marker row (month {@code null}). */
    static Map<String, Object> monthlyVerified(ReportFilter filter) {
        return query(filter, "orders",
                List.of(ORDERS_REVENUE, ORDERS_COUNT, ORDERS_UNITS, ORDERS_VERSION), List.of(),
                timeDimension(ORDERS_SOLD_AT, "month", null), List.of(periodOrMarkers(filter, "orders")));
    }

    /** Distinct orders in the period (rollup {@code orders.daily_by_store}); one row. */
    static Map<String, Object> orderTotals(ReportFilter filter) {
        return query(filter, "orders", List.of(ORDERS_COUNT, ORDERS_VERSION), List.of(),
                timeDimension(ORDERS_SOLD_AT, null, filter), periodFilters(filter, "orders"));
    }

    /** {@link #orderTotals} including the business's marker row (which counts no order). */
    static Map<String, Object> orderTotalsVerified(ReportFilter filter) {
        return query(filter, "orders", List.of(ORDERS_COUNT, ORDERS_VERSION), List.of(),
                null, List.of(periodOrMarkers(filter, "orders")));
    }

    /**
     * Revenue, units and orders containing the category, per category with sales in the period
     * (rollup {@code order_categories.daily_by_store_category}).
     */
    static Map<String, Object> categories(ReportFilter filter) {
        return query(filter, "order_categories",
                List.of(CATEGORY_REVENUE, CATEGORY_UNITS, CATEGORY_ORDERS, CATEGORY_VERSION), List.of(CATEGORY),
                timeDimension(CATEGORY_SOLD_AT, null, filter), periodFilters(filter, "order_categories"));
    }

    /**
     * {@link #categories} plus the marker rows: one per catalogue category (so every category is
     * listed, with null figures when it has no sales) and one for the business (category {@code null}).
     */
    static Map<String, Object> categoriesVerified(ReportFilter filter) {
        return query(filter, "order_categories",
                List.of(CATEGORY_REVENUE, CATEGORY_UNITS, CATEGORY_ORDERS, CATEGORY_VERSION), List.of(CATEGORY),
                null, List.of(periodOrMarkers(filter, "order_categories")));
    }

    /**
     * The business's catalogue: every category with its version, plus the business's marker row
     * (category {@code null}), from the same rollup as {@link #categories}. No period: the marker rows
     * are always included, so the answer always carries the build's version.
     */
    static Map<String, Object> catalogue(ReportFilter filter) {
        return query(filter, "order_categories", List.of(CATEGORY_VERSION), List.of(CATEGORY),
                null, List.of());
    }

    private static Map<String, Object> query(
            ReportFilter filter,
            String cube,
            List<String> measures,
            List<String> dimensions,
            Map<String, Object> timeDimension,
            List<Map<String, Object>> filters) {
        List<Map<String, Object>> allFilters = new ArrayList<>();
        allFilters.add(Map.of("member", cube + ".business_id", "operator", "equals",
                "values", List.of(String.valueOf(filter.businessId()))));
        allFilters.addAll(filters);
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("measures", measures);
        if (!dimensions.isEmpty()) {
            query.put("dimensions", dimensions);
        }
        if (timeDimension != null) {
            query.put("timeDimensions", List.of(timeDimension));
        }
        query.put("filters", allFilters);
        query.put("timezone", filter.zone().getId());
        query.put("limit", ROW_LIMIT);
        return query;
    }

    /** The time dimension, with the filter's dates as {@code dateRange} when {@code period} is given. */
    private static Map<String, Object> timeDimension(String member, String granularity, ReportFilter period) {
        Map<String, Object> timeDimension = new LinkedHashMap<>();
        timeDimension.put("dimension", member);
        if (granularity != null) {
            timeDimension.put("granularity", granularity);
        }
        if (period != null) {
            timeDimension.put("dateRange", List.of(period.from().toString(), period.to().toString()));
        }
        return timeDimension;
    }

    /** The optional store filter of the rollup form (the period is the time dimension's dateRange). */
    private static List<Map<String, Object>> periodFilters(ReportFilter filter, String cube) {
        return filter.storeId() == null ? List.of() : List.of(storeFilter(filter, cube));
    }

    /** {@code (sold_at in [from, to] [AND store_id = s]) OR sold_at is not set (marker rows)}. */
    private static Map<String, Object> periodOrMarkers(ReportFilter filter, String cube) {
        Map<String, Object> period = Map.of("member", cube + ".sold_at", "operator", "inDateRange",
                "values", List.of(filter.from().toString(), filter.to().toString()));
        Map<String, Object> sales = filter.storeId() == null
                ? period
                : Map.of("and", List.of(period, storeFilter(filter, cube)));
        Map<String, Object> markers = Map.of("member", cube + ".sold_at", "operator", "notSet");
        return Map.of("or", List.of(sales, markers));
    }

    private static Map<String, Object> storeFilter(ReportFilter filter, String cube) {
        return Map.of("member", cube + ".store_id", "operator", "equals",
                "values", List.of(String.valueOf(filter.storeId())));
    }
}
