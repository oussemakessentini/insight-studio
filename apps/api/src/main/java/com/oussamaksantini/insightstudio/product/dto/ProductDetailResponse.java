package com.oussamaksantini.insightstudio.product.dto;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import com.oussamaksantini.insightstudio.reporting.MetricValue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A product with its sales performance for the period, compared with the preceding period.
 *
 * @param averageSellingPrice revenue / units; its {@code value} is {@code null} when nothing sold
 * @param priceHistory each distinct unit price charged in the period, in the order it was first used
 */
public record ProductDetailResponse(
        ProductInfo product,
        DateRange period,
        DateRange previousPeriod,
        Long storeId,
        MetricValue revenue,
        MetricValue unitsSold,
        MetricValue orders,
        MetricValue averageSellingPrice,
        List<PriceHistoryEntry> priceHistory) {

    /** @param firstSoldOn first local date the price was charged within the period */
    public record PriceHistoryEntry(
            BigDecimal unitPrice, LocalDate firstSoldOn, LocalDate lastSoldOn, long unitsSold, long orders) {
    }
}
