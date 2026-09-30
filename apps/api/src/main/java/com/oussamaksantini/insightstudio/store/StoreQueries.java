package com.oussamaksantini.insightstudio.store;

import static com.oussamaksantini.insightstudio.reporting.ReportSql.SALES_FROM;
import static com.oussamaksantini.insightstudio.reporting.ReportSql.params;
import static com.oussamaksantini.insightstudio.reporting.ReportSql.salesWhere;

import com.oussamaksantini.insightstudio.reporting.ReportCalculations;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Store reporting queries, one SQL statement per result. Revenue is
 * {@code sale_items.quantity * sale_items.unit_price}; an order is a receipt with at least one line
 * item (see {@link com.oussamaksantini.insightstudio.reporting.ReportSql}).
 */
@Repository
class StoreQueries {

    private final NamedParameterJdbcTemplate jdbc;

    StoreQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record StoreRow(
            long id, String code, String name, String city,
            BigDecimal revenue, long orders, long units, BigDecimal previousRevenue) {
    }

    record Totals(BigDecimal revenue, long orders, long units) {
    }

    record CategoryRow(String category, BigDecimal revenue, long units, long orders) {
    }

    /**
     * Every store of the business with its totals for {@code filter} and its revenue in
     * {@code previous}, in a single pass. The previous window ends exactly where the current one
     * starts, so one join over {@code [previous start, current end)} covers both. Receipts without
     * items have no {@code sale_items} row, so they add no revenue and are never counted as orders.
     */
    List<StoreRow> storesWithPrevious(ReportFilter filter, ReportFilter previous) {
        String sql = """
                SELECT st.id, st.code, st.name, st.city,
                       COALESCE(SUM(si.quantity * si.unit_price) FILTER (WHERE s.sold_at >= :start), 0) AS revenue,
                       COUNT(DISTINCT si.sale_id) FILTER (WHERE s.sold_at >= :start)                    AS orders,
                       COALESCE(SUM(si.quantity) FILTER (WHERE s.sold_at >= :start), 0)                 AS units,
                       COALESCE(SUM(si.quantity * si.unit_price) FILTER (WHERE s.sold_at < :start), 0)  AS previous_revenue
                FROM stores st
                LEFT JOIN sales s ON s.store_id = st.id AND s.sold_at >= :previousStart AND s.sold_at < :end
                LEFT JOIN sale_items si ON si.sale_id = s.id
                WHERE st.business_id = :businessId
                GROUP BY st.id, st.code, st.name, st.city
                ORDER BY revenue DESC, st.name, st.id
                """;
        MapSqlParameterSource params = params(filter).addValue("previousStart", previous.start());
        return jdbc.query(sql, params, (rs, i) -> new StoreRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("city"),
                ReportCalculations.money(rs.getBigDecimal("revenue")),
                rs.getLong("orders"),
                rs.getLong("units"),
                ReportCalculations.money(rs.getBigDecimal("previous_revenue"))));
    }

    /** Totals for the filter's window and store (the filter carries the store id). */
    Totals totals(ReportFilter filter) {
        String sql = """
                SELECT COALESCE(SUM(si.quantity * si.unit_price), 0) AS revenue,
                       COUNT(DISTINCT s.id)                          AS orders,
                       COALESCE(SUM(si.quantity), 0)                 AS units
                """ + SALES_FROM + salesWhere(filter);
        return jdbc.queryForObject(sql, params(filter), (rs, i) -> new Totals(
                ReportCalculations.money(rs.getBigDecimal("revenue")), rs.getLong("orders"), rs.getLong("units")));
    }

    /** Revenue, units and orders per product category, highest revenue first. */
    List<CategoryRow> categories(ReportFilter filter) {
        String sql = """
                SELECT p.category,
                       SUM(si.quantity * si.unit_price) AS revenue,
                       SUM(si.quantity)                 AS units,
                       COUNT(DISTINCT s.id)             AS orders
                """ + SALES_FROM + """
                JOIN products p ON p.id = si.product_id
                """ + salesWhere(filter) + """
                GROUP BY p.category
                ORDER BY revenue DESC, p.category
                """;
        return jdbc.query(sql, params(filter), (rs, i) -> new CategoryRow(
                rs.getString("category"),
                ReportCalculations.money(rs.getBigDecimal("revenue")),
                rs.getLong("units"),
                rs.getLong("orders")));
    }

    /** Inserts a store into the business unless the code is taken there; empty on a conflict. */
    Optional<Long> insertStore(long businessId, String code, String name, String city) {
        return jdbc.queryForList("""
                INSERT INTO stores (business_id, code, name, city) VALUES (:businessId, :code, :name, :city)
                ON CONFLICT (business_id, code) DO NOTHING
                RETURNING id
                """,
                new MapSqlParameterSource()
                        .addValue("businessId", businessId)
                        .addValue("code", code)
                        .addValue("name", name)
                        .addValue("city", city),
                Long.class).stream().findFirst();
    }
}
