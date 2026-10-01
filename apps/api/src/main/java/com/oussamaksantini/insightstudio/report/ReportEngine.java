package com.oussamaksantini.insightstudio.report;

import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Where the raw per-period totals of the reports come from (docs/cube-reports-contract.md §3):
 * {@link SqlReportEngine} (PostgreSQL) or {@link CubeReportEngine} (Cube), chosen by
 * {@code insight.reports.engine}. Engines only fetch totals; {@link ReportService} does every
 * calculation (zero-filled months, averages, shares, rounding), so the JSON, CSV and PDF output is
 * the same whichever engine runs.
 *
 * <p>Both engines follow the same definitions: revenue is {@code quantity * unit_price} (the price
 * charged), an order is a receipt with at least one line item, and dates are calendar dates in the
 * business's time zone.
 */
interface ReportEngine {

    /** The engine's name as sent in {@code X-Report-Engine}: {@code sql} or {@code cube}. */
    String name();

    /** Months with at least one order, bucketed in the business's time zone, in order. */
    List<MonthTotals> monthly(ReportFilter filter);

    /**
     * Every category of the business's catalogue (zeros for categories without sales in the window)
     * sorted by revenue (highest first), then name in the database's collation, and the overall
     * total, whose orders are distinct across categories.
     */
    CategoryBreakdown categories(ReportFilter filter);

    record MonthTotals(LocalDate month, BigDecimal revenue, long orders, long units) {
    }

    record CategoryTotals(String category, BigDecimal revenue, long units, long orders) {
    }

    /** Category rows plus the grand total, whose orders are distinct across categories. */
    record CategoryBreakdown(List<CategoryTotals> categories, CategoryTotals total) {
    }
}
