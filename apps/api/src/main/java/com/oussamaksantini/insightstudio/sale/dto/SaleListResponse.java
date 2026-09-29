package com.oussamaksantini.insightstudio.sale.dto;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One page of orders in the period: receipts with at least one line item. {@code totalItems}
 * equals the dashboard's order count for the same date and store filters.
 *
 * @param product the product filter, when {@code productId} was given
 */
public record SaleListResponse(
        DateRange period,
        Long storeId,
        String query,
        ProductRef product,
        String sort,
        int page,
        int size,
        long totalItems,
        int totalPages,
        List<Item> items) {

    public record ProductRef(long id, String sku, String name) {
    }

    /**
     * @param lineCount distinct products on the receipt (line items)
     * @param unitCount total quantity across all lines
     * @param total sum of quantity x unit price actually charged, for the whole receipt
     */
    public record Item(
            long saleId,
            String receiptNumber,
            Instant soldAt,
            long storeId,
            String storeCode,
            String storeName,
            long lineCount,
            long unitCount,
            BigDecimal total) {
    }
}
