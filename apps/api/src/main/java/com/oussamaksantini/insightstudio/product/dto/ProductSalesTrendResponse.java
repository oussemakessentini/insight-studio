package com.oussamaksantini.insightstudio.product.dto;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import com.oussamaksantini.insightstudio.reporting.Granularity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Sales of one product per bucket; every bucket overlapping the period is present. */
public record ProductSalesTrendResponse(
        long productId, DateRange period, Long storeId, Granularity granularity, List<Point> points) {

    /**
     * @param averageUnitPrice revenue / units at the prices charged, or {@code null} when nothing sold
     * @param complete whether the whole bucket lies inside the period (see the dashboard revenue series)
     */
    public record Point(
            LocalDate periodStart,
            BigDecimal revenue,
            long unitsSold,
            long orders,
            BigDecimal averageUnitPrice,
            int daysCovered,
            int bucketDays,
            boolean complete) {
    }
}
