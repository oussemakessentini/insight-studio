package com.oussamaksantini.insightstudio.store.dto;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import com.oussamaksantini.insightstudio.reporting.MetricValue;
import java.math.BigDecimal;
import java.util.List;

/**
 * One store's performance for the period, compared with the preceding period of equal length.
 *
 * @param categories revenue per product category, highest first
 */
public record StoreDetailResponse(
        StoreInfo store,
        DateRange period,
        DateRange previousPeriod,
        MetricValue revenue,
        MetricValue orders,
        MetricValue unitsSold,
        MetricValue averageOrderValue,
        List<CategorySales> categories) {

    /**
     * @param orders receipts containing at least one product of the category; a receipt spanning
     *     several categories counts once in each, so these do not add up to the store's orders
     * @param revenueSharePercent share of the store's revenue in the period
     */
    public record CategorySales(
            String category, BigDecimal revenue, long unitsSold, long orders, BigDecimal revenueSharePercent) {
    }
}
