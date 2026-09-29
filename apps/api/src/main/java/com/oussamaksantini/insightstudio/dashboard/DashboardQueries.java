package com.oussamaksantini.insightstudio.dashboard;

import com.oussamaksantini.insightstudio.dashboard.dto.DateRange;
import com.oussamaksantini.insightstudio.dashboard.dto.RecentSalesResponse.RecentSale;
import com.oussamaksantini.insightstudio.dashboard.dto.StoreSalesResponse.StoreSales;
import com.oussamaksantini.insightstudio.dashboard.dto.TopProductsResponse.TopProduct;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Aggregate queries behind the dashboard. Each method runs a single SQL statement; revenue is
 * always {@code sale_items.quantity * sale_items.unit_price}, the price actually charged.
 */
@Repository
class DashboardQueries {

    /** Joins shared by the sales queries; the filter comes from {@link #where(ReportFilter)}. */
    private static final String SALES_FROM = """
            FROM sales s
            JOIN stores st ON st.id = s.store_id
            JOIN sale_items si ON si.sale_id = s.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    DashboardQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Totals(BigDecimal revenue, long orders, long units) {
    }

    record RevenueBucket(LocalDate bucketStart, BigDecimal revenue, long orders) {
    }

    Totals totals(ReportFilter filter) {
        String sql = """
                SELECT COALESCE(SUM(si.quantity * si.unit_price), 0) AS revenue,
                       COUNT(DISTINCT s.id)                          AS orders,
                       COALESCE(SUM(si.quantity), 0)                 AS units
                """ + SALES_FROM + where(filter);
        return jdbc.queryForObject(sql, params(filter), (rs, i) -> new Totals(
                rs.getBigDecimal("revenue"), rs.getLong("orders"), rs.getLong("units")));
    }

    List<RevenueBucket> revenueByBucket(ReportFilter filter, Granularity granularity) {
        // The unit comes from the enum, never from user input, so it is safe to inline.
        String sql = """
                SELECT CAST(date_trunc('%s', s.sold_at AT TIME ZONE :tz) AS date) AS bucket,
                       SUM(si.quantity * si.unit_price)                           AS revenue,
                       COUNT(DISTINCT s.id)                                       AS orders
                """.formatted(granularity.sqlUnit()) + SALES_FROM + where(filter) + """
                GROUP BY 1
                ORDER BY 1
                """;
        return jdbc.query(sql, params(filter), (rs, i) -> new RevenueBucket(
                rs.getObject("bucket", LocalDate.class), rs.getBigDecimal("revenue"), rs.getLong("orders")));
    }

    /** Every store of the business (or just the filtered one), including stores without sales. */
    List<StoreSales> salesByStore(ReportFilter filter) {
        String sql = """
                SELECT st.id, st.code, st.name, st.city,
                       COALESCE(SUM(si.quantity * si.unit_price), 0) AS revenue,
                       COUNT(DISTINCT s.id)                          AS orders,
                       COALESCE(SUM(si.quantity), 0)                 AS units
                FROM stores st
                LEFT JOIN sales s ON s.store_id = st.id AND s.sold_at >= :start AND s.sold_at < :end
                LEFT JOIN sale_items si ON si.sale_id = s.id
                WHERE st.business_id = :businessId
                """ + (filter.storeId() != null ? "  AND st.id = :storeId\n" : "") + """
                GROUP BY st.id, st.code, st.name, st.city
                ORDER BY revenue DESC, st.name
                """;
        return jdbc.query(sql, params(filter), (rs, i) -> new StoreSales(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("city"),
                DashboardCalculations.money(rs.getBigDecimal("revenue")),
                rs.getLong("orders"),
                rs.getLong("units"),
                null));
    }

    List<TopProduct> topProducts(ReportFilter filter, int limit) {
        String sql = """
                SELECT p.id, p.sku, p.name, p.category,
                       SUM(si.quantity)                 AS units,
                       SUM(si.quantity * si.unit_price) AS revenue
                """ + SALES_FROM + """
                JOIN products p ON p.id = si.product_id
                """ + where(filter) + """
                GROUP BY p.id, p.sku, p.name, p.category
                ORDER BY revenue DESC, units DESC, p.id
                LIMIT :limit
                """;
        MapSqlParameterSource params = params(filter).addValue("limit", limit);
        return jdbc.query(sql, params, (rs, i) -> {
            BigDecimal revenue = DashboardCalculations.money(rs.getBigDecimal("revenue"));
            long units = rs.getLong("units");
            return new TopProduct(
                    rs.getLong("id"),
                    rs.getString("sku"),
                    rs.getString("name"),
                    rs.getString("category"),
                    units,
                    revenue,
                    DashboardCalculations.average(revenue, units));
        });
    }

    List<RecentSale> recentSales(ReportFilter filter, int limit) {
        String sql = """
                SELECT s.id, s.receipt_number, s.sold_at, st.id AS store_id, st.name AS store_name,
                       SUM(si.quantity)                 AS items,
                       SUM(si.quantity * si.unit_price) AS total
                """ + SALES_FROM + where(filter) + """
                GROUP BY s.id, st.id
                ORDER BY s.sold_at DESC, s.id DESC
                LIMIT :limit
                """;
        MapSqlParameterSource params = params(filter).addValue("limit", limit);
        return jdbc.query(sql, params, (rs, i) -> new RecentSale(
                rs.getLong("id"),
                rs.getString("receipt_number"),
                rs.getObject("sold_at", OffsetDateTime.class).toInstant(),
                rs.getLong("store_id"),
                rs.getString("store_name"),
                rs.getLong("items"),
                DashboardCalculations.money(rs.getBigDecimal("total"))));
    }

    /** First and last local sale dates for the business, or {@code null} when it has no sales. */
    DateRange saleDateRange(long businessId, ZoneId zone) {
        String sql = """
                SELECT MIN(s.sold_at) AS first_sale, MAX(s.sold_at) AS last_sale
                FROM sales s
                JOIN stores st ON st.id = s.store_id
                WHERE st.business_id = :businessId
                """;
        return jdbc.queryForObject(sql, Map.of("businessId", businessId), (rs, i) -> {
            OffsetDateTime first = rs.getObject("first_sale", OffsetDateTime.class);
            OffsetDateTime last = rs.getObject("last_sale", OffsetDateTime.class);
            if (first == null || last == null) {
                return null;
            }
            return new DateRange(
                    first.atZoneSameInstant(zone).toLocalDate(),
                    last.atZoneSameInstant(zone).toLocalDate());
        });
    }

    private static String where(ReportFilter filter) {
        return "WHERE st.business_id = :businessId AND s.sold_at >= :start AND s.sold_at < :end\n"
                + (filter.storeId() != null ? "  AND s.store_id = :storeId\n" : "");
    }

    private static MapSqlParameterSource params(ReportFilter filter) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("businessId", filter.businessId());
        values.put("start", filter.start());
        values.put("end", filter.endExclusive());
        values.put("tz", filter.zone().getId());
        if (filter.storeId() != null) {
            values.put("storeId", filter.storeId());
        }
        return new MapSqlParameterSource(values);
    }
}
