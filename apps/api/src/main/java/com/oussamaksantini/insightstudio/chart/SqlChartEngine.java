package com.oussamaksantini.insightstudio.chart;

import com.oussamaksantini.insightstudio.common.web.ServiceUnavailableException;
import com.oussamaksantini.insightstudio.report.CubeFreshness;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Chart figures from PostgreSQL. Only fixed statements run: one template per grouping
 * ({@link #TEMPLATES}) plus constant filter conditions, with every value (business, dates, time zone,
 * bucket unit, filter ids and names) a bound parameter; nothing from the request is ever part of the
 * SQL text.
 *
 * <p>Each statement computes the groups and the period's total together ({@code GROUPING SETS}), so
 * the total's orders are distinct across groups, and it runs in the caller's read-only transaction
 * after {@code set_config('statement_timeout', ..., true)} (the same as
 * {@code SET LOCAL statement_timeout}): a statement cancelled by the timeout is a 503 with the report
 * engine's "temporarily unavailable" message.
 */
final class SqlChartEngine implements ChartEngine {

    static final String NAME = "sql";
    /** PostgreSQL's query_canceled, raised when statement_timeout cancels a statement. */
    static final String QUERY_CANCELED = "57014";

    private static final Logger log = LoggerFactory.getLogger(SqlChartEngine.class);

    /** The group key of every line item, per grouping (constants; the bucket unit is a parameter). */
    private static final Map<ChartGroupBy, String> GROUP_KEYS = new EnumMap<>(Map.of(
            ChartGroupBy.TIME, "CAST(date_trunc(:unit, s.sold_at AT TIME ZONE :tz) AS date)",
            ChartGroupBy.STORE, "s.store_id",
            ChartGroupBy.PRODUCT, "si.product_id",
            ChartGroupBy.CATEGORY, "p.category"));

    /** The line items of the business in the period; the filter conditions below are appended. */
    private static final String LINES = """
            WITH lines AS (
                SELECT %s AS group_key, s.id AS sale_id, si.quantity, si.unit_price
                FROM sales s
                JOIN stores st ON st.id = s.store_id
                JOIN sale_items si ON si.sale_id = s.id
                JOIN products p ON p.id = si.product_id
                WHERE st.business_id = :businessId AND s.sold_at >= :start AND s.sold_at < :end
            """;
    private static final String STORE_FILTER = "      AND s.store_id IN (:storeIds)\n";
    private static final String PRODUCT_FILTER = "      AND si.product_id IN (:productIds)\n";
    private static final String CATEGORY_FILTER = "      AND p.category IN (:categories)\n";
    private static final String FIGURES = """
                   COALESCE(SUM(quantity * unit_price), 0) AS revenue,
                   COUNT(DISTINCT sale_id)                 AS orders,
                   COALESCE(SUM(quantity), 0)              AS units
            FROM lines
            """;
    /** The statement of each grouping, before the filter conditions (see {@link #sql}). */
    static final Map<ChartGroupBy, String[]> TEMPLATES = templates();

    private final NamedParameterJdbcTemplate jdbc;
    private final Duration statementTimeout;

    SqlChartEngine(NamedParameterJdbcTemplate jdbc, Duration statementTimeout) {
        this.jdbc = jdbc;
        this.statementTimeout = statementTimeout;
    }

    private static Map<ChartGroupBy, String[]> templates() {
        Map<ChartGroupBy, String[]> templates = new EnumMap<>(ChartGroupBy.class);
        for (ChartGroupBy groupBy : ChartGroupBy.values()) {
            String key = groupBy == ChartGroupBy.NONE ? "NULL" : GROUP_KEYS.get(groupBy);
            String select = groupBy == ChartGroupBy.NONE
                    ? ")\nSELECT NULL AS group_key, 1 AS is_total,\n" + FIGURES
                    : ")\nSELECT group_key, GROUPING(group_key) AS is_total,\n" + FIGURES
                            + "GROUP BY GROUPING SETS ((group_key), ())\n";
            templates.put(groupBy, new String[] {LINES.formatted(key), select});
        }
        return Map.copyOf(templates);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ChartFigures figures(ChartQuery query) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Chart queries must run in a (read-only) transaction");
        }
        ReportFilter period = query.period();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("businessId", period.businessId())
                .addValue("start", period.start())
                .addValue("end", period.endExclusive())
                .addValue("tz", period.zone().getId());
        if (query.granularity() != null) {
            params.addValue("unit", query.granularity().sqlUnit());
        }
        if (!query.filters().storeIds().isEmpty()) {
            params.addValue("storeIds", query.filters().storeIds());
        }
        if (!query.filters().productIds().isEmpty()) {
            params.addValue("productIds", query.filters().productIds());
        }
        if (!query.filters().categories().isEmpty()) {
            params.addValue("categories", query.filters().categories());
        }

        Map<String, Figures> groups = new LinkedHashMap<>();
        Figures[] total = {Figures.ZERO};
        try {
            // Local to the transaction, like SET LOCAL; a bound value instead of SQL text.
            jdbc.queryForObject("SELECT set_config('statement_timeout', :timeout, true)",
                    Map.of("timeout", Long.toString(statementTimeout.toMillis())), String.class);
            jdbc.query(sql(query), params, rs -> {
                Figures figures = new Figures(rs.getBigDecimal("revenue"), rs.getLong("orders"), rs.getLong("units"));
                if (rs.getInt("is_total") == 1) {
                    total[0] = figures;
                } else {
                    groups.put(key(rs.getObject("group_key")), figures);
                }
            });
        } catch (DataAccessException e) {
            if (canceled(e)) {
                log.warn("A chart query for business {} ran longer than {} and was cancelled", period.businessId(),
                        statementTimeout);
                throw new ServiceUnavailableException(CubeFreshness.UNAVAILABLE, CubeFreshness.RETRY_AFTER_BUILDING);
            }
            throw e;
        }
        return new ChartFigures(groups, new Figures(
                total[0].revenue() == null ? BigDecimal.ZERO : total[0].revenue(), total[0].orders(), total[0].units()));
    }

    /** The grouping's template with the constant conditions of the filters in use. */
    static String sql(ChartQuery query) {
        String[] template = TEMPLATES.get(query.groupBy());
        StringBuilder sql = new StringBuilder(template[0]);
        if (!query.filters().storeIds().isEmpty()) {
            sql.append(STORE_FILTER);
        }
        if (!query.filters().productIds().isEmpty()) {
            sql.append(PRODUCT_FILTER);
        }
        if (!query.filters().categories().isEmpty()) {
            sql.append(CATEGORY_FILTER);
        }
        return sql.append(template[1]).toString();
    }

    private static String key(Object value) {
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        return String.valueOf(value);
    }

    private static boolean canceled(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
