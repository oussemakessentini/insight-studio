package com.oussamaksantini.insightstudio.report;

import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.report.dto.CategoryReportResponse;
import com.oussamaksantini.insightstudio.report.dto.MonthlyReportResponse;
import com.oussamaksantini.insightstudio.report.pdf.ReportPdf;
import com.oussamaksantini.insightstudio.report.pdf.ReportPdfDetails;
import com.oussamaksantini.insightstudio.reporting.DateRange;
import com.oussamaksantini.insightstudio.reporting.ReportingContext;
import com.oussamaksantini.insightstudio.store.StoreRepository;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand reports. Date and store parameters behave exactly as on the dashboard endpoints; the
 * {@code .csv} variants return the same rows as a downloadable file, the {@code .pdf} variants a
 * printable document built from the same response (docs/saved-reports-contract.md §4).
 */
@RestController
@RequestMapping("/api/reports")
class ReportController {

    private final ReportService reports;
    private final ReportingContext reporting;
    private final StoreRepository stores;
    private final ReportPdf pdf;

    ReportController(ReportService reports, ReportingContext reporting, StoreRepository stores, ReportPdf pdf) {
        this.reports = reports;
        this.reporting = reporting;
        this.stores = stores;
        this.pdf = pdf;
    }

    @GetMapping("/monthly")
    MonthlyReportResponse monthly(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId) {
        return reports.monthly(from, to, storeId);
    }

    @GetMapping("/categories")
    CategoryReportResponse categories(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId) {
        return reports.categories(from, to, storeId);
    }

    @GetMapping("/monthly.csv")
    ResponseEntity<String> monthlyCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId) {
        MonthlyReportResponse report = reports.monthly(from, to, storeId);
        return ReportFiles.csv(csvFilename("monthly", report.period()), ReportCsv.monthly(report));
    }

    @GetMapping("/categories.csv")
    ResponseEntity<String> categoriesCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId) {
        CategoryReportResponse report = reports.categories(from, to, storeId);
        return ReportFiles.csv(csvFilename("categories", report.period()), ReportCsv.categories(report));
    }

    @GetMapping("/monthly.pdf")
    ResponseEntity<byte[]> monthlyPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId) {
        MonthlyReportResponse report = reports.monthly(from, to, storeId);
        Business business = reporting.currentBusiness();
        byte[] body = pdf.monthly(report, details("Monthly report", business, report.storeId()));
        return ReportFiles.pdf(ReportFiles.filename(business.getSlug(), "monthly", report.period(), "pdf"), body);
    }

    @GetMapping("/categories.pdf")
    ResponseEntity<byte[]> categoriesPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId) {
        CategoryReportResponse report = reports.categories(from, to, storeId);
        Business business = reporting.currentBusiness();
        byte[] body = pdf.categories(report, details("Category report", business, report.storeId()));
        return ReportFiles.pdf(ReportFiles.filename(business.getSlug(), "categories", report.period(), "pdf"), body);
    }

    /** Labels only; the report has already checked that the store belongs to the business. */
    private ReportPdfDetails details(String title, Business business, Long storeId) {
        String store = storeId == null ? ReportPdfDetails.ALL_STORES : stores.findByIdAndBusinessId(storeId, business.getId())
                .map(s -> ReportPdfDetails.storeLabel(s.getName(), s.getCode()))
                .orElse(ReportPdfDetails.ALL_STORES);
        return ReportPdfDetails.of(title, business, store, null);
    }

    /** Unchanged since the CSV export was introduced: only the report name and the resolved ISO dates. */
    private static String csvFilename(String name, DateRange period) {
        return "%s-%s-to-%s.csv".formatted(name, period.from(), period.to());
    }
}
