package com.oussamaksantini.insightstudio.report;

import static com.oussamaksantini.insightstudio.reporting.ReportSql.SALES_FROM;
import static com.oussamaksantini.insightstudio.reporting.ReportSql.params;
import static com.oussamaksantini.insightstudio.reporting.ReportSql.salesWhere;

import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Aggregate queries behind the reports. Each method runs a single SQL statement; revenue is
 * {@code sale_items.quantity * sale_items.unit_price} and an order is a receipt with at least one
 * line item (enforced by the inner join in {@link com.oussamaksantini.insightstudio.reporting.ReportSql#SALES_FROM}).
 */
@Repository
class ReportQueries {

    private final NamedParameterJdbcTemplate jdbc;

    ReportQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record MonthTotals(LocalDate month, BigDecimal revenue, long orders, long units) {
    }

    record CategoryTotals(String category, BigDecimal revenue, long units, long orders) {
    }

    /** Category rows plus the grand total, whose orders are distinct across categories. */
    record CategoryBreakdown(List<CategoryTotals> categories, CategoryTotals total) {
    }

    /** Months with at least one order, bucketed in the business's time zone, in order. */
    List<MonthTotals> monthly(ReportFilter filter) {
        String sql = """
                SELECT CAST(date_trunc('month', s.sold_at AT TIME ZONE :tz) AS date) AS month,
                       SUM(si.quantity * si.unit_price)                             AS revenue,
                       COUNT(DISTINCT s.id)                                         AS orders,
                       SUM(si.quantity)                                             AS units
                """ + SALES_FROM + salesWhere(filter) + """
                GROUP BY 1
                ORDER BY 1
                """;
        return jdbc.query(sql, params(filter), (rs, i) -> new MonthTotals(
                rs.getObject("month", LocalDate.class),
                rs.getBigDecimal("revenue"),
                rs.getLong("orders"),
                rs.getLong("units")));
    }

    /**
     * Every category of the business's catalogue (categories without sales in the window get zeros)
     * and, through {@code GROUPING SETS}, the overall total in the same statement. Sorted by revenue
     * (highest first), then name.
     */
    CategoryBreakdown categories(ReportFilter filter) {
        String sql = """
                WITH lines AS (
                    SELECT si.product_id, si.sale_id, si.quantity, si.unit_price
                """ + indent(SALES_FROM + salesWhere(filter)) + """
                )
                SELECT p.category,
                       GROUPING(p.category)                       AS is_total,
                       COALESCE(SUM(l.quantity * l.unit_price), 0) AS revenue,
                       COALESCE(SUM(l.quantity), 0)                AS units,
                       COUNT(DISTINCT l.sale_id)                   AS orders
                FROM products p
                LEFT JOIN lines l ON l.product_id = p.id
                WHERE p.business_id = :businessId
                GROUP BY GROUPING SETS ((p.category), ())
                ORDER BY is_total, revenue DESC, p.category
                """;
        List<CategoryTotals> rows = new ArrayList<>();
        CategoryTotals[] total = {new CategoryTotals(null, BigDecimal.ZERO, 0, 0)};
        jdbc.query(sql, params(filter), rs -> {
            CategoryTotals row = new CategoryTotals(
                    rs.getString("category"), rs.getBigDecimal("revenue"), rs.getLong("units"), rs.getLong("orders"));
            if (rs.getInt("is_total") == 1) {
                total[0] = row;
            } else {
                rows.add(row);
            }
        });
        return new CategoryBreakdown(rows, total[0]);
    }

    private static String indent(String sql) {
        return sql.lines().map(line -> "    " + line + "\n").reduce("", String::concat);
    }
}
