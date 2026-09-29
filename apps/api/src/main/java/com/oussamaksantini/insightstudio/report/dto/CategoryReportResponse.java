package com.oussamaksantini.insightstudio.report.dto;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.math.BigDecimal;
import java.util.List;

/**
 * Sales per product category, sorted by revenue (highest first). Every category of the business's
 * catalogue is present, with zeros where there were no sales.
 */
public record CategoryReportResponse(DateRange period, Long storeId, List<Row> rows, Totals totals) {

    /**
     * @param orders receipts containing at least one product of this category; a receipt spanning
     *     several categories counts once in each, so these do not add up to {@link Totals#orders()}
     * @param averageUnitPrice revenue per unit sold, or zero without sales
     */
    public record Row(
            String category,
            BigDecimal revenue,
            long unitsSold,
            long orders,
            BigDecimal revenueSharePercent,
            BigDecimal averageUnitPrice) {
    }

    /** @param orders distinct orders across all categories */
    public record Totals(
            BigDecimal revenue,
            long unitsSold,
            long orders,
            BigDecimal averageOrderValue,
            BigDecimal averageUnitPrice) {
    }
}
