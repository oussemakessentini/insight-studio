package com.oussamaksantini.insightstudio.product.dto;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.math.BigDecimal;
import java.util.List;

/**
 * One page of the product catalogue with sales performance for the period. Products without sales
 * in the period are included with zero values.
 */
public record ProductListResponse(
        DateRange period,
        Long storeId,
        String query,
        String category,
        String sort,
        String direction,
        int page,
        int size,
        long totalItems,
        int totalPages,
        List<Item> items) {

    /** @param averageSellingPrice revenue / units at the prices charged, or {@code null} without sales */
    public record Item(
            long productId,
            String sku,
            String name,
            String category,
            BigDecimal listPrice,
            long unitsSold,
            long orders,
            BigDecimal revenue,
            BigDecimal averageSellingPrice) {
    }
}
