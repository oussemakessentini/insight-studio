package com.oussamaksantini.insightstudio.dashboard.dto;

import com.oussamaksantini.insightstudio.dashboard.Granularity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Revenue per bucket. Every bucket overlapping the period is present, with zeros where there were no sales. */
public record RevenueSeriesResponse(DateRange period, Long storeId, Granularity granularity, List<Point> points) {

    /**
     * @param periodStart first day of the bucket (may precede the requested period for weeks/months)
     * @param daysCovered days of this bucket that fall inside the requested period
     * @param bucketDays total days in the bucket (1, 7, or the month length)
     * @param complete whether the whole bucket lies inside the requested period; partial buckets
     *     hold fewer days of sales and should not be compared like-for-like
     */
    public record Point(
            LocalDate periodStart,
            BigDecimal revenue,
            long orders,
            int daysCovered,
            int bucketDays,
            boolean complete) {
    }
}
