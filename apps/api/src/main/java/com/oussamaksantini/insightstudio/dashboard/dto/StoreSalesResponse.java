package com.oussamaksantini.insightstudio.dashboard.dto;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.math.BigDecimal;
import java.util.List;

/** Sales per store, including stores with no sales in the period. Sorted by revenue, highest first. */
public record StoreSalesResponse(DateRange period, BigDecimal totalRevenue, List<StoreSales> stores) {

    public record StoreSales(
            long storeId,
            String code,
            String name,
            String city,
            BigDecimal revenue,
            long orders,
            long unitsSold,
            BigDecimal revenueSharePercent) {
    }
}
