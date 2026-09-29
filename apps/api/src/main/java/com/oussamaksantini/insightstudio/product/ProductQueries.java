package com.oussamaksantini.insightstudio.product;

import static com.oussamaksantini.insightstudio.reporting.ReportSql.SALES_FROM;
import static com.oussamaksantini.insightstudio.reporting.ReportSql.params;
import static com.oussamaksantini.insightstudio.reporting.ReportSql.salesWhere;

import com.oussamaksantini.insightstudio.product.dto.ProductDetailResponse.PriceHistoryEntry;
import com.oussamaksantini.insightstudio.product.dto.ProductListResponse.Item;
import com.oussamaksantini.insightstudio.reporting.Granularity;
import com.oussamaksantini.insightstudio.reporting.ReportCalculations;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Product reporting queries. Revenue uses the unit price stored on each sale item, so it reflects
 * the prices actually charged rather than the current list price.
 */
@Repository
class ProductQueries {

    private final NamedParameterJdbcTemplate jdbc;

    ProductQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Catalogue search criteria; {@code search} and {@code category} are optional. */
    record Criteria(String search, String category) {
    }

    record Totals(BigDecimal revenue, long units, long orders) {
    }

    record TrendBucket(LocalDate bucketStart, BigDecimal revenue, long units, long orders) {
    }

    /** One page of products (including those without sales), ordered by {@code sort}, then id for stable paging. */
    List<Item> page(ReportFilter filter, Criteria criteria, ProductSort sort, ProductSort.Direction direction,
            int limit, long offset) {
        String sql = """
                WITH sold AS (
                    SELECT si.product_id,
                           SUM(si.quantity)                 AS units,
                           SUM(si.quantity * si.unit_price) AS revenue,
                           COUNT(DISTINCT s.id)             AS orders
                """ + indent(SALES_FROM + salesWhere(filter)) + """
                    GROUP BY si.product_id
                )
                SELECT p.id, p.sku, p.name, p.category, p.list_price,
                       COALESCE(sold.units, 0)   AS units,
                       COALESCE(sold.revenue, 0) AS revenue,
                       COALESCE(sold.orders, 0)  AS orders
                FROM products p
                LEFT JOIN sold ON sold.product_id = p.id
                """ + catalogueWhere(criteria)
                + "ORDER BY " + sort.sqlExpression() + " " + direction.name() + ", p.id\n"
                + "LIMIT :limit OFFSET :offset\n";
        MapSqlParameterSource params = catalogueParams(params(filter), criteria)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(sql, params, (rs, i) -> {
            long units = rs.getLong("units");
            BigDecimal revenue = ReportCalculations.money(rs.getBigDecimal("revenue"));
            return new Item(
                    rs.getLong("id"),
                    rs.getString("sku"),
                    rs.getString("name"),
                    rs.getString("category"),
                    rs.getBigDecimal("list_price"),
                    units,
                    rs.getLong("orders"),
                    revenue,
                    units == 0 ? null : ReportCalculations.average(revenue, units));
        });
    }

    long count(long businessId, Criteria criteria) {
        String sql = "SELECT COUNT(*) FROM products p\n" + catalogueWhere(criteria);
        MapSqlParameterSource params = catalogueParams(
                new MapSqlParameterSource("businessId", businessId), criteria);
        return jdbc.queryForObject(sql, params, Long.class);
    }

    List<String> categories(long businessId) {
        return jdbc.queryForList(
                "SELECT DISTINCT category FROM products WHERE business_id = :businessId ORDER BY category",
                Map.of("businessId", businessId), String.class);
    }

    Totals totals(ReportFilter filter, long productId) {
        String sql = """
                SELECT COALESCE(SUM(si.quantity * si.unit_price), 0) AS revenue,
                       COALESCE(SUM(si.quantity), 0)                 AS units,
                       COUNT(DISTINCT s.id)                          AS orders
                """ + SALES_FROM + salesWhere(filter) + "  AND si.product_id = :productId\n";
        return jdbc.queryForObject(sql, params(filter).addValue("productId", productId), (rs, i) -> new Totals(
                rs.getBigDecimal("revenue"), rs.getLong("units"), rs.getLong("orders")));
    }

    List<PriceHistoryEntry> priceHistory(ReportFilter filter, long productId) {
        String sql = """
                SELECT si.unit_price,
                       MIN(s.sold_at)       AS first_sold,
                       MAX(s.sold_at)       AS last_sold,
                       SUM(si.quantity)     AS units,
                       COUNT(DISTINCT s.id) AS orders
                """ + SALES_FROM + salesWhere(filter) + """
                  AND si.product_id = :productId
                GROUP BY si.unit_price
                ORDER BY first_sold, si.unit_price
                """;
        return jdbc.query(sql, params(filter).addValue("productId", productId), (rs, i) -> new PriceHistoryEntry(
                rs.getBigDecimal("unit_price"),
                localDate(rs.getObject("first_sold", OffsetDateTime.class), filter),
                localDate(rs.getObject("last_sold", OffsetDateTime.class), filter),
                rs.getLong("units"),
                rs.getLong("orders")));
    }

    List<TrendBucket> trend(ReportFilter filter, long productId, Granularity granularity) {
        String sql = """
                SELECT CAST(date_trunc('%s', s.sold_at AT TIME ZONE :tz) AS date) AS bucket,
                       SUM(si.quantity * si.unit_price)                           AS revenue,
                       SUM(si.quantity)                                           AS units,
                       COUNT(DISTINCT s.id)                                       AS orders
                """.formatted(granularity.sqlUnit()) + SALES_FROM + salesWhere(filter) + """
                  AND si.product_id = :productId
                GROUP BY 1
                ORDER BY 1
                """;
        return jdbc.query(sql, params(filter).addValue("productId", productId), (rs, i) -> new TrendBucket(
                rs.getObject("bucket", LocalDate.class),
                rs.getBigDecimal("revenue"),
                rs.getLong("units"),
                rs.getLong("orders")));
    }

    private static String catalogueWhere(Criteria criteria) {
        StringBuilder where = new StringBuilder("WHERE p.business_id = :businessId\n");
        if (criteria.search() != null) {
            where.append("  AND (p.name ILIKE :search OR p.sku ILIKE :search)\n");
        }
        if (criteria.category() != null) {
            where.append("  AND p.category = :category\n");
        }
        return where.toString();
    }

    private static MapSqlParameterSource catalogueParams(MapSqlParameterSource params, Criteria criteria) {
        if (criteria.search() != null) {
            params.addValue("search", "%" + escapeLike(criteria.search()) + "%");
        }
        if (criteria.category() != null) {
            params.addValue("category", criteria.category());
        }
        return params;
    }

    /** Escapes LIKE wildcards so user input is matched literally (PostgreSQL's default escape is backslash). */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static LocalDate localDate(OffsetDateTime instant, ReportFilter filter) {
        return instant.atZoneSameInstant(filter.zone()).toLocalDate();
    }

    private static String indent(String sql) {
        return sql.lines().map(line -> "    " + line).reduce("", (a, b) -> a + b + "\n");
    }
}
