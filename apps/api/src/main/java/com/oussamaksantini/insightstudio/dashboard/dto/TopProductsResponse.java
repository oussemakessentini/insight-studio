package com.oussamaksantini.insightstudio.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

/** Best-selling products by revenue. */
public record TopProductsResponse(DateRange period, Long storeId, List<TopProduct> products) {

    /** @param averageUnitPrice revenue / units, reflecting the prices actually charged */
    public record TopProduct(
            long productId,
            String sku,
            String name,
            String category,
            long unitsSold,
            BigDecimal revenue,
            BigDecimal averageUnitPrice) {
    }
}
