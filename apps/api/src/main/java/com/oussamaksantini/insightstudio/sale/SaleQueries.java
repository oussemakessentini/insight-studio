package com.oussamaksantini.insightstudio.sale;

import static com.oussamaksantini.insightstudio.reporting.ReportSql.containsPattern;
import static com.oussamaksantini.insightstudio.reporting.ReportSql.params;

import com.oussamaksantini.insightstudio.reporting.ReportCalculations;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import com.oussamaksantini.insightstudio.reporting.ReportSql;
import com.oussamaksantini.insightstudio.sale.dto.SaleDetailResponse.Line;
import com.oussamaksantini.insightstudio.sale.dto.SaleDetailResponse.StoreInfo;
import com.oussamaksantini.insightstudio.sale.dto.SaleListResponse.Item;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Sales register queries. Totals use {@code sale_items.unit_price}, the price actually charged.
 * The list only contains orders, i.e. receipts with at least one line item (see {@link ReportSql}),
 * so its count always equals the dashboard's order count for the same filters. A receipt without
 * items can still be opened by id through {@link #header} and {@link #lines}.
 */
@Repository
class SaleQueries {

    private final NamedParameterJdbcTemplate jdbc;

    SaleQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Optional list criteria on top of the date/store filter. */
    record Criteria(String receiptSearch, Long productId) {
    }

    record Header(long saleId, String receiptNumber, Instant soldAt, StoreInfo store) {
    }

    List<Item> page(ReportFilter filter, Criteria criteria, SaleSort sort, int limit, long offset) {
        String sql = """
                SELECT s.id, s.receipt_number, s.sold_at, st.id AS store_id, st.code AS store_code, st.name AS store_name,
                       COUNT(si.id)                     AS lines,
                       SUM(si.quantity)                 AS units,
                       SUM(si.quantity * si.unit_price) AS total
                FROM sales s
                JOIN stores st ON st.id = s.store_id
                JOIN sale_items si ON si.sale_id = s.id
                """ + where(filter, criteria) + """
                GROUP BY s.id, st.id
                ORDER BY %s
                LIMIT :limit OFFSET :offset
                """.formatted(sort.orderBy());
        MapSqlParameterSource params = criteriaParams(filter, criteria)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(sql, params, (rs, i) -> new Item(
                rs.getLong("id"),
                rs.getString("receipt_number"),
                rs.getObject("sold_at", OffsetDateTime.class).toInstant(),
                rs.getLong("store_id"),
                rs.getString("store_code"),
                rs.getString("store_name"),
                rs.getLong("lines"),
                rs.getLong("units"),
                ReportCalculations.money(rs.getBigDecimal("total"))));
    }

    long count(ReportFilter filter, Criteria criteria) {
        String sql = "SELECT COUNT(*)\nFROM sales s\nJOIN stores st ON st.id = s.store_id\n" + where(filter, criteria);
        return jdbc.queryForObject(sql, criteriaParams(filter, criteria), Long.class);
    }

    Optional<Header> header(long saleId, long businessId) {
        String sql = """
                SELECT s.id, s.receipt_number, s.sold_at, st.id AS store_id, st.code, st.name, st.city
                FROM sales s
                JOIN stores st ON st.id = s.store_id
                WHERE s.id = :saleId AND st.business_id = :businessId
                """;
        return jdbc.query(sql, Map.of("saleId", saleId, "businessId", businessId), (rs, i) -> new Header(
                        rs.getLong("id"),
                        rs.getString("receipt_number"),
                        rs.getObject("sold_at", OffsetDateTime.class).toInstant(),
                        new StoreInfo(rs.getLong("store_id"), rs.getString("code"), rs.getString("name"), rs.getString("city"))))
                .stream()
                .findFirst();
    }

    List<Line> lines(long saleId) {
        String sql = """
                SELECT p.id, p.sku, p.name, p.category, p.list_price, si.quantity, si.unit_price
                FROM sale_items si
                JOIN products p ON p.id = si.product_id
                WHERE si.sale_id = :saleId
                ORDER BY si.id
                """;
        return jdbc.query(sql, Map.of("saleId", saleId), (rs, i) -> {
            int quantity = rs.getInt("quantity");
            BigDecimal unitPrice = rs.getBigDecimal("unit_price");
            return new Line(
                    rs.getLong("id"),
                    rs.getString("sku"),
                    rs.getString("name"),
                    rs.getString("category"),
                    quantity,
                    unitPrice,
                    ReportCalculations.money(unitPrice.multiply(BigDecimal.valueOf(quantity))),
                    rs.getBigDecimal("list_price"));
        });
    }

    /**
     * Same business/time/store filter as other reports, limited to orders. {@link ReportSql#HAS_ITEMS}
     * keeps {@link #count} (which doesn't join items) equal to the page query.
     */
    private static String where(ReportFilter filter, Criteria criteria) {
        StringBuilder where = new StringBuilder(
                "WHERE st.business_id = :businessId AND s.sold_at >= :start AND s.sold_at < :end\n")
                .append("  AND ").append(ReportSql.HAS_ITEMS);
        if (filter.storeId() != null) {
            where.append("  AND s.store_id = :storeId\n");
        }
        if (criteria.receiptSearch() != null) {
            where.append("  AND s.receipt_number ILIKE :receipt\n");
        }
        if (criteria.productId() != null) {
            where.append("  AND EXISTS (SELECT 1 FROM sale_items x WHERE x.sale_id = s.id AND x.product_id = :productId)\n");
        }
        return where.toString();
    }

    private static MapSqlParameterSource criteriaParams(ReportFilter filter, Criteria criteria) {
        MapSqlParameterSource params = params(filter);
        if (criteria.receiptSearch() != null) {
            params.addValue("receipt", containsPattern(criteria.receiptSearch()));
        }
        if (criteria.productId() != null) {
            params.addValue("productId", criteria.productId());
        }
        return params;
    }
}
