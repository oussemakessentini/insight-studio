package com.oussamaksantini.insightstudio.store.dto;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.math.BigDecimal;
import java.util.List;

/**
 * Performance of every store of the business, including stores without orders in the period.
 * Sorted by revenue (highest first), then name.
 */
public record StoreListResponse(DateRange period, DateRange previousPeriod, List<StorePerformance> stores) {

    /**
     * @param revenueSharePercent share of the revenue of all stores in the period
     * @param revenueChangePercent change versus the previous period, or {@code null} when the store
     *     had no revenue then
     */
    public record StorePerformance(
            long storeId,
            String code,
            String name,
            String city,
            BigDecimal revenue,
            long orders,
            long unitsSold,
            BigDecimal averageOrderValue,
            BigDecimal revenueSharePercent,
            BigDecimal revenueChangePercent) {
    }
}
