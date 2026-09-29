package com.oussamaksantini.insightstudio.reporting;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * SQL fragments shared by reporting queries. Revenue is always
 * {@code sale_items.quantity * sale_items.unit_price}, the price actually charged at the time of sale.
 */
public final class ReportSql {

    /** Joins used by queries over sales; aliases {@code s}, {@code st} and {@code si}. */
    public static final String SALES_FROM = """
            FROM sales s
            JOIN stores st ON st.id = s.store_id
            JOIN sale_items si ON si.sale_id = s.id
            """;

    private ReportSql() {
    }

    /** WHERE clause restricting {@link #SALES_FROM} to the filter's business, time window and optional store. */
    public static String salesWhere(ReportFilter filter) {
        return "WHERE st.business_id = :businessId AND s.sold_at >= :start AND s.sold_at < :end\n"
                + (filter.storeId() != null ? "  AND s.store_id = :storeId\n" : "");
    }

    /** Named parameters for {@link #salesWhere}: businessId, start, end, tz and (when set) storeId. */
    public static MapSqlParameterSource params(ReportFilter filter) {
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
