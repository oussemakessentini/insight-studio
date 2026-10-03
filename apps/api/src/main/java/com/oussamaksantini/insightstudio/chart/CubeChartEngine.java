package com.oussamaksantini.insightstudio.chart;

import static com.oussamaksantini.insightstudio.chart.CubeChartQueries.ORDERS;
import static com.oussamaksantini.insightstudio.chart.CubeChartQueries.ORDER_CATEGORIES;
import static com.oussamaksantini.insightstudio.chart.CubeChartQueries.ORDER_PRODUCTS;
import static com.oussamaksantini.insightstudio.chart.CubeChartQueries.member;
import static com.oussamaksantini.insightstudio.report.CubeFreshness.count;
import static com.oussamaksantini.insightstudio.report.CubeFreshness.localDate;
import static com.oussamaksantini.insightstudio.report.CubeFreshness.money;

import com.oussamaksantini.insightstudio.report.CubeFreshness;
import com.oussamaksantini.insightstudio.report.CubeFreshness.Attempt;
import com.oussamaksantini.insightstudio.report.CubeFreshness.Verified;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chart figures from Cube ({@code "engine": "cube"}), with the report engine's freshness check,
 * deadline, revalidation and 503 answers ({@link CubeFreshness}): figures are never older than the
 * data version read at the start of the request.
 *
 * <p>Which query answers what is described in {@link CubeChartQueries}. Queries that Cube serves from a
 * rollup are sent in the rollup form first and in the verified form when the period has no sales;
 * queries that need distinct orders across several products or categories cannot use a rollup and are
 * sent in the verified form only. All answers of one chart must share one data version.
 */
final class CubeChartEngine implements ChartEngine {

    static final String NAME = "cube";

    private static final String REVENUE = "revenue";
    private static final String COUNT = "count";
    private static final String DISTINCT_ORDERS = "distinct_orders";
    private static final String UNITS = "units";
    private static final String VERSION = "data_version";

    private final CubeFreshness freshness;

    CubeChartEngine(CubeFreshness freshness) {
        this.freshness = freshness;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ChartFigures figures(ChartQuery query) {
        return freshness.run(query.businessId(), attempt -> switch (query.groupBy()) {
            case NONE, TIME, STORE -> byOrder(attempt, query);
            case PRODUCT, CATEGORY -> byItem(attempt, query);
        });
    }

    /**
     * Time, store or no grouping: every order falls into exactly one group, so the total is the sum
     * of the groups.
     */
    private ChartFigures byOrder(Attempt attempt, ChartQuery query) {
        Map<String, Figures> groups;
        if (query.filters().filtersItems()) {
            String cube = ORDER_PRODUCTS;
            List<String> measures = measures(cube, DISTINCT_ORDERS);
            groups = groups(cube, attempt.database(CubeChartQueries.verified(cube, measures, query, true),
                    member(cube, VERSION)), query, DISTINCT_ORDERS);
        } else {
            String cube = ORDERS;
            List<String> measures = measures(cube, COUNT);
            groups = groups(cube, attempt.verified(CubeChartQueries.rollup(cube, measures, query, true),
                    CubeChartQueries.verified(cube, measures, query, true), member(cube, VERSION)), query, COUNT);
        }
        Figures total = Figures.ZERO;
        for (Figures group : groups.values()) {
            total = total.plus(group);
        }
        if (query.groupBy() == ChartGroupBy.NONE) {
            return new ChartFigures(Map.of(), total);
        }
        return new ChartFigures(groups, total);
    }

    /**
     * Product or category grouping: an order can be in several groups, so the total's orders come from
     * a query of their own (distinct orders); revenue and units add up.
     */
    private ChartFigures byItem(Attempt attempt, ChartQuery query) {
        Verified rows;
        String cube;
        String orders;
        if (query.groupBy() == ChartGroupBy.PRODUCT) {
            cube = ORDER_PRODUCTS;
            orders = COUNT;
            List<String> measures = measures(cube, orders);
            rows = attempt.verified(CubeChartQueries.rollup(cube, measures, query, true),
                    CubeChartQueries.verified(cube, measures, query, true), member(cube, VERSION));
        } else if (query.filters().productIds().isEmpty()) {
            cube = ORDER_CATEGORIES;
            orders = COUNT;
            List<String> measures = measures(cube, orders);
            rows = attempt.verified(CubeChartQueries.rollup(cube, measures, query, true),
                    CubeChartQueries.verified(cube, measures, query, true), member(cube, VERSION));
        } else {
            cube = ORDER_PRODUCTS;
            orders = DISTINCT_ORDERS;
            rows = attempt.database(CubeChartQueries.verified(cube, measures(cube, orders), query, true),
                    member(cube, VERSION));
        }
        Verified distinct;
        long totalOrders;
        if (query.filters().filtersItems()) {
            List<String> measures = List.of(member(ORDER_PRODUCTS, DISTINCT_ORDERS), member(ORDER_PRODUCTS, VERSION));
            distinct = attempt.database(CubeChartQueries.verified(ORDER_PRODUCTS, measures, query, false),
                    member(ORDER_PRODUCTS, VERSION));
            totalOrders = sum(distinct, member(ORDER_PRODUCTS, DISTINCT_ORDERS));
        } else {
            List<String> measures = List.of(member(ORDERS, COUNT), member(ORDERS, VERSION));
            distinct = attempt.verified(CubeChartQueries.rollup(ORDERS, measures, query, false),
                    CubeChartQueries.verified(ORDERS, measures, query, false), member(ORDERS, VERSION));
            totalOrders = sum(distinct, member(ORDERS, COUNT));
        }
        attempt.requireOneVersion(rows, distinct);

        Map<String, Figures> groups = groups(cube, rows, query, orders);
        Figures sum = Figures.ZERO;
        for (Figures group : groups.values()) {
            sum = sum.plus(group);
        }
        return new ChartFigures(groups, new Figures(sum.revenue(), totalOrders, sum.units()));
    }

    private static List<String> measures(String cube, String orders) {
        return List.of(member(cube, REVENUE), member(cube, orders), member(cube, UNITS), member(cube, VERSION));
    }

    /** Figures per group key; marker rows (no key) and rows without orders are left out. */
    private static Map<String, Figures> groups(String cube, Verified answer, ChartQuery query, String orders) {
        String keyMember = query.groupBy() == ChartGroupBy.TIME
                ? CubeChartQueries.timeKey(cube, query)
                : CubeChartQueries.dimension(cube, query);
        Map<String, Figures> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : answer.rows()) {
            Figures figures = new Figures(money(row.get(member(cube, REVENUE))), count(row.get(member(cube, orders))),
                    count(row.get(member(cube, UNITS))));
            if (figures.orders() == 0) {
                continue; // a marker row, or a catalogue category without sales
            }
            String key;
            if (keyMember == null) {
                key = "";
            } else {
                Object value = row.get(keyMember);
                if (value == null) {
                    continue;
                }
                key = switch (query.groupBy()) {
                    case TIME -> localDate(value).toString();
                    case STORE, PRODUCT -> Long.toString(count(value));
                    default -> value.toString();
                };
            }
            groups.merge(key, figures, Figures::plus);
        }
        return groups;
    }

    private static long sum(Verified answer, String member) {
        long total = 0;
        for (Map<String, Object> row : answer.rows()) {
            total += count(row.get(member));
        }
        return total;
    }
}
