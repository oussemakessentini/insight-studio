package com.oussamaksantini.insightstudio.sale.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A receipt with its line items. The schema records no customer or payment details. A receipt
 * without line items can be opened but is not an order: it is excluded from lists and counts.
 */
public record SaleDetailResponse(
        long saleId,
        String receiptNumber,
        Instant soldAt,
        StoreInfo store,
        long lineCount,
        long unitCount,
        BigDecimal total,
        List<Line> lines) {

    public record StoreInfo(long id, String code, String name, String city) {
    }

    /**
     * @param unitPrice price charged at the time of sale
     * @param currentListPrice the product's list price today, for comparison only
     */
    public record Line(
            long productId,
            String sku,
            String name,
            String category,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal currentListPrice) {
    }
}
