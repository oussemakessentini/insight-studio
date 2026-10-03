package com.oussamaksantini.insightstudio.report;

import static com.oussamaksantini.insightstudio.report.CubeFreshness.count;
import static com.oussamaksantini.insightstudio.report.CubeFreshness.localDate;
import static com.oussamaksantini.insightstudio.report.CubeFreshness.money;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.CATEGORY;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.CATEGORY_ORDERS;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.CATEGORY_REVENUE;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.CATEGORY_UNITS;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.CATEGORY_VERSION;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.ORDERS_COUNT;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.ORDERS_MONTH;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.ORDERS_REVENUE;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.ORDERS_UNITS;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.ORDERS_VERSION;

import com.oussamaksantini.insightstudio.analytics.CubeClient;
import com.oussamaksantini.insightstudio.report.CubeFreshness.Verified;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Cube report engine ({@code insight.reports.engine=cube}): the reports' raw totals from Cube's
 * REST API (docs/cube-reports.md), never older than the data they are asked about.
 *
 * <p><b>Freshness.</b> Each request first reads {@code report_data_version} (Flyway V11, bumped in the
 * same transaction as any change to sales, sale items, products or stores). Every Cube query returns
 * the cube's {@code data_version}: the version read by the same SQL statement as the rows it was
 * computed from (a rollup build, or the query itself). A result counts only when that version is at
 * least the one read at the start; the queries are built so that every answer carries it
 * ({@link CubeReportQueries}). When an answer is older, the whole report is asked again with
 * {@code "cache": "must-revalidate"}, which makes Cube re-read its refresh keys and rebuild, until
 * the deadline. The figures of one report must also come from a single version.
 *
 * <p><b>Failures</b> (docs/cube-reports-contract.md §5) are 503 problem details with a
 * {@code Retry-After}; Cube's own messages are only logged. One deadline ({@code cube-timeout})
 * covers all Cube calls of a request. The checks, retries and failure mapping live in
 * {@link CubeFreshness}, which the chart builder's Cube engine shares.
 */
final class CubeReportEngine implements ReportEngine {

    static final String NAME = "cube";
    static final String UNAVAILABLE = CubeFreshness.UNAVAILABLE;
    static final String UPDATING = CubeFreshness.UPDATING;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CubeFreshness freshness;
    private final UnaryOperator<List<CategoryTotals>> categoryOrder;

    /**
     * @param dataVersion reads the current {@code report_data_version}
     * @param categoryOrder sorts category rows by revenue (highest first), then name in the database's
     *     collation, exactly like {@link SqlReportEngine}
     */
    CubeReportEngine(
            CubeFreshness.Cube cube,
            LongSupplier dataVersion,
            UnaryOperator<List<CategoryTotals>> categoryOrder,
            Duration timeout) {
        this.freshness = new CubeFreshness(cube, dataVersion, timeout);
        this.categoryOrder = categoryOrder;
    }

    /** The engine wired to Cube and the database. */
    static CubeReportEngine create(CubeClient client, NamedParameterJdbcTemplate jdbc, Duration timeout) {
        return new CubeReportEngine(client::send, () -> CubeFreshness.currentDataVersion(jdbc),
                rows -> orderInDatabase(jdbc, rows), timeout);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<MonthTotals> monthly(ReportFilter filter) {
        return freshness.run(filter.businessId(), attempt -> {
            Verified answer = attempt.verified(
                    CubeReportQueries.monthly(filter), CubeReportQueries.monthlyVerified(filter), ORDERS_VERSION);
            List<MonthTotals> months = new ArrayList<>();
            for (Map<String, Object> row : answer.rows()) {
                Object month = row.get(ORDERS_MONTH);
                if (month == null) {
                    continue; // the business's marker row
                }
                months.add(new MonthTotals(
                        localDate(month), money(row.get(ORDERS_REVENUE)), count(row.get(ORDERS_COUNT)),
                        count(row.get(ORDERS_UNITS))));
            }
            months.sort(Comparator.comparing(MonthTotals::month));
            return months;
        });
    }

    @Override
    public CategoryBreakdown categories(ReportFilter filter) {
        return freshness.run(filter.businessId(), attempt -> {
            Verified sold = attempt.verified(
                    CubeReportQueries.categories(filter), CubeReportQueries.categoriesVerified(filter), CATEGORY_VERSION);
            Verified catalogue = attempt.verified(CubeReportQueries.catalogue(filter), null, CATEGORY_VERSION);
            Verified orders = attempt.verified(
                    CubeReportQueries.orderTotals(filter), CubeReportQueries.orderTotalsVerified(filter), ORDERS_VERSION);
            attempt.requireOneVersion(sold, catalogue, orders);

            // Every catalogue category, with its figures when it sold in the period (zeros otherwise).
            Map<String, CategoryTotals> byCategory = new LinkedHashMap<>();
            for (Map<String, Object> row : catalogue.rows()) {
                if (row.get(CATEGORY) instanceof String name) {
                    byCategory.put(name, new CategoryTotals(name, money(null), 0, 0));
                }
            }
            for (Map<String, Object> row : sold.rows()) {
                if (row.get(CATEGORY) instanceof String name) {
                    byCategory.put(name, new CategoryTotals(name, money(row.get(CATEGORY_REVENUE)),
                            count(row.get(CATEGORY_UNITS)), count(row.get(CATEGORY_ORDERS))));
                }
            }
            BigDecimal revenue = money(null);
            long units = 0;
            for (CategoryTotals category : byCategory.values()) {
                revenue = revenue.add(category.revenue());
                units += category.units();
            }
            long totalOrders = orders.rows().isEmpty() ? 0 : count(orders.rows().getFirst().get(ORDERS_COUNT));
            List<CategoryTotals> rows = byCategory.isEmpty()
                    ? List.of()
                    : categoryOrder.apply(List.copyOf(byCategory.values()));
            return new CategoryBreakdown(rows, new CategoryTotals(null, revenue, units, totalOrders));
        });
    }

    // ---------------------------------------------------------------- database helpers

    /**
     * Sorts like {@link SqlReportEngine}'s {@code ORDER BY revenue DESC, p.category}, in the database,
     * so names tie-break in the same collation.
     */
    static List<CategoryTotals> orderInDatabase(NamedParameterJdbcTemplate jdbc, List<CategoryTotals> rows) {
        List<Map<String, String>> values = rows.stream()
                .map(row -> Map.of("category", row.category(), "revenue", row.revenue().toPlainString()))
                .toList();
        Map<String, CategoryTotals> byName = new LinkedHashMap<>();
        rows.forEach(row -> byName.put(row.category(), row));
        List<String> ordered = jdbc.queryForList("""
                SELECT t.category
                FROM jsonb_to_recordset(CAST(:rows AS jsonb)) AS t(category varchar, revenue numeric)
                ORDER BY t.revenue DESC, t.category
                """, Map.of("rows", JSON.writeValueAsString(values)), String.class);
        return ordered.stream().map(byName::get).toList();
    }
}
