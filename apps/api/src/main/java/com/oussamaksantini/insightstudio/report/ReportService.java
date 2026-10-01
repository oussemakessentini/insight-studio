package com.oussamaksantini.insightstudio.report;

import com.oussamaksantini.insightstudio.report.ReportEngine.CategoryBreakdown;
import com.oussamaksantini.insightstudio.report.ReportEngine.CategoryTotals;
import com.oussamaksantini.insightstudio.report.ReportEngine.MonthTotals;
import com.oussamaksantini.insightstudio.report.dto.CategoryReportResponse;
import com.oussamaksantini.insightstudio.report.dto.MonthlyReportResponse;
import com.oussamaksantini.insightstudio.reporting.Granularity;
import com.oussamaksantini.insightstudio.reporting.ReportCalculations;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import com.oussamaksantini.insightstudio.reporting.ReportingContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The monthly and category reports. The raw per-period totals come from the configured
 * {@link ReportEngine} (SQL or Cube); every calculation on them happens here, so responses, CSV and
 * PDF files are identical for both engines (docs/cube-reports-contract.md §3).
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    private final ReportingContext reporting;
    private final ReportEngine engine;

    ReportService(ReportingContext reporting, ReportEngine engine) {
        this.reporting = reporting;
        this.engine = engine;
    }

    public MonthlyReportResponse monthly(LocalDate from, LocalDate to, Long storeId) {
        ReportFilter filter = reporting.resolveFilter(from, to, storeId);
        Map<LocalDate, MonthTotals> byMonth = engine.monthly(filter).stream()
                .collect(Collectors.toMap(MonthTotals::month, Function.identity()));

        List<MonthlyReportResponse.Row> rows = new ArrayList<>();
        BigDecimal totalRevenue = BigDecimal.ZERO;
        long totalOrders = 0;
        long totalUnits = 0;
        BigDecimal previousRevenue = null;
        for (ReportCalculations.Bucket bucket : ReportCalculations.buckets(filter.from(), filter.to(), Granularity.MONTH)) {
            MonthTotals month = byMonth.get(bucket.start());
            BigDecimal revenue = ReportCalculations.money(month == null ? BigDecimal.ZERO : month.revenue());
            long orders = month == null ? 0 : month.orders();
            long units = month == null ? 0 : month.units();
            rows.add(new MonthlyReportResponse.Row(
                    bucket.start(),
                    revenue,
                    orders,
                    units,
                    ReportCalculations.averageOrderValue(revenue, orders),
                    ReportCalculations.percentChange(revenue, previousRevenue),
                    bucket.daysCovered(),
                    bucket.bucketDays(),
                    bucket.complete()));
            previousRevenue = revenue;
            totalRevenue = totalRevenue.add(revenue);
            // Every order falls in exactly one month, so month counts add up to the period's orders.
            totalOrders += orders;
            totalUnits += units;
        }

        var totals = new MonthlyReportResponse.Totals(
                totalRevenue, totalOrders, totalUnits, ReportCalculations.averageOrderValue(totalRevenue, totalOrders));
        return new MonthlyReportResponse(filter.period(), filter.storeId(), rows, totals);
    }

    public CategoryReportResponse categories(LocalDate from, LocalDate to, Long storeId) {
        ReportFilter filter = reporting.resolveFilter(from, to, storeId);
        CategoryBreakdown breakdown = engine.categories(filter);
        CategoryTotals total = breakdown.total();
        BigDecimal totalRevenue = ReportCalculations.money(total.revenue());

        List<CategoryReportResponse.Row> rows = breakdown.categories().stream()
                .map(c -> {
                    BigDecimal revenue = ReportCalculations.money(c.revenue());
                    return new CategoryReportResponse.Row(
                            c.category(),
                            revenue,
                            c.units(),
                            c.orders(),
                            ReportCalculations.sharePercent(revenue, totalRevenue),
                            ReportCalculations.average(revenue, c.units()));
                })
                .toList();

        var totals = new CategoryReportResponse.Totals(
                totalRevenue,
                total.units(),
                total.orders(),
                ReportCalculations.averageOrderValue(totalRevenue, total.orders()),
                ReportCalculations.average(totalRevenue, total.units()));
        return new CategoryReportResponse(filter.period(), filter.storeId(), rows, totals);
    }
}
