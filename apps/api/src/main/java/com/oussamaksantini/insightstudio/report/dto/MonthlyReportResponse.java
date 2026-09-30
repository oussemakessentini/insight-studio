package com.oussamaksantini.insightstudio.report.dto;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Sales per calendar month (in the business's time zone). Every month overlapping the period is
 * present, with zeros where there were no sales.
 */
public record MonthlyReportResponse(DateRange period, Long storeId, List<Row> rows, Totals totals) {

    /**
     * @param month first day of the month (may precede the requested period)
     * @param revenueChangePercent change from the previous row; {@code null} for the first row or
     *     when the previous month had no revenue
     * @param daysCovered days of this month inside the requested period
     * @param daysInMonth total days in the month
     * @param complete whether the whole month lies inside the requested period
     */
    public record Row(
            LocalDate month,
            BigDecimal revenue,
            long orders,
            long unitsSold,
            BigDecimal averageOrderValue,
            BigDecimal revenueChangePercent,
            int daysCovered,
            int daysInMonth,
            boolean complete) {
    }

    public record Totals(BigDecimal revenue, long orders, long unitsSold, BigDecimal averageOrderValue) {
    }
}
