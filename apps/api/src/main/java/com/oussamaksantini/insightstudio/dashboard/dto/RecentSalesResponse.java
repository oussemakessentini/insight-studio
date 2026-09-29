package com.oussamaksantini.insightstudio.dashboard.dto;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Most recent sales in the period, newest first. */
public record RecentSalesResponse(DateRange period, Long storeId, List<RecentSale> sales) {

    public record RecentSale(
            long saleId,
            String receiptNumber,
            Instant soldAt,
            long storeId,
            String storeName,
            long itemCount,
            BigDecimal total) {
    }
}
