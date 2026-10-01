package com.oussamaksantini.insightstudio.report;

import com.oussamaksantini.insightstudio.report.dto.CategoryReportResponse;
import com.oussamaksantini.insightstudio.report.dto.MonthlyReportResponse;

/** CSV renderings of the reports: a header row followed by the same rows as the JSON (no totals row). */
public final class ReportCsv {

    private ReportCsv() {
    }

    public static String monthly(MonthlyReportResponse report) {
        CsvWriter csv = new CsvWriter().header(
                "month", "revenue", "orders", "units_sold", "average_order_value",
                "revenue_change_percent", "days_covered", "days_in_month", "complete");
        for (MonthlyReportResponse.Row r : report.rows()) {
            csv.literal(r.month())
                    .number(r.revenue())
                    .number(r.orders())
                    .number(r.unitsSold())
                    .number(r.averageOrderValue())
                    .number(r.revenueChangePercent())
                    .number(r.daysCovered())
                    .number(r.daysInMonth())
                    .literal(r.complete())
                    .endRow();
        }
        return csv.toString();
    }

    public static String categories(CategoryReportResponse report) {
        CsvWriter csv = new CsvWriter().header(
                "category", "revenue", "units_sold", "orders", "revenue_share_percent", "average_unit_price");
        for (CategoryReportResponse.Row r : report.rows()) {
            csv.text(r.category())
                    .number(r.revenue())
                    .number(r.unitsSold())
                    .number(r.orders())
                    .number(r.revenueSharePercent())
                    .number(r.averageUnitPrice())
                    .endRow();
        }
        return csv.toString();
    }
}
