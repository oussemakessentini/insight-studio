package com.oussamaksantini.insightstudio.chart;

import com.oussamaksantini.insightstudio.chart.ChartDefinition.Filters;
import com.oussamaksantini.insightstudio.reporting.Granularity;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Where a chart's raw figures come from: {@link SqlChartEngine} (PostgreSQL) or {@link CubeChartEngine}
 * (Cube), chosen per definition ({@code engine}). Engines only fetch revenue, orders and units per
 * group and for the whole period; {@link ChartResults} turns them into the result (zero-filled
 * buckets, labels, ranking, averages), so both engines give the same answer.
 *
 * <p>Definitions (docs/chart-builder-contract.md §1): revenue is {@code quantity * unit_price} (the
 * price charged); an order is a receipt with at least one line item matching the filters, counted
 * once per group it has items in; units are quantities; dates are local dates in the business's time
 * zone. Filters on products and categories select line items; the store filter selects receipts.
 */
interface ChartEngine {

    /** The engine's name as sent in {@code X-Report-Engine}: {@code sql} or {@code cube}. */
    String name();

    ChartFigures figures(ChartQuery query);

    /**
     * What to compute.
     *
     * @param period the business, its time zone and the inclusive local dates ({@code storeId} unused)
     * @param granularity the bucket size when grouping by time, otherwise {@code null}
     */
    record ChartQuery(ReportFilter period, ChartGroupBy groupBy, Granularity granularity, Filters filters) {

        long businessId() {
            return period.businessId();
        }
    }

    /** Revenue (raw sum), distinct orders and units of a group or of the whole period. */
    record Figures(BigDecimal revenue, long orders, long units) {

        static final Figures ZERO = new Figures(BigDecimal.ZERO, 0, 0);

        Figures plus(Figures other) {
            return new Figures(revenue.add(other.revenue), orders + other.orders, units + other.units);
        }
    }

    /**
     * Figures per group with sales and for the whole period. Group keys: the bucket's first day
     * ({@code 2026-03-02}) for time, the id for stores and products, the name for categories; empty
     * for {@link ChartGroupBy#NONE}. {@code total.orders} counts distinct orders, so for products and
     * categories it is not the sum of the groups.
     */
    record ChartFigures(Map<String, Figures> groups, Figures total) {
    }
}
