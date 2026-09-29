package com.oussamaksantini.insightstudio.report;

import com.oussamaksantini.insightstudio.report.dto.CategoryReportResponse;
import com.oussamaksantini.insightstudio.report.dto.MonthlyReportResponse;
import com.oussamaksantini.insightstudio.reporting.DateRange;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand reports. Date and store parameters behave exactly as on the dashboard endpoints; the
 * {@code .csv} variants return the same rows as a downloadable file.
 */
@RestController
@RequestMapping("/api/reports")
class ReportController {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
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
        return csv("monthly", report.period(), ReportCsv.monthly(report));
    }

    @GetMapping("/categories.csv")
    ResponseEntity<String> categoriesCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId) {
        CategoryReportResponse report = reports.categories(from, to, storeId);
        return csv("categories", report.period(), ReportCsv.categories(report));
    }

    private static ResponseEntity<String> csv(String name, DateRange period, String body) {
        // The filename holds only the report name and the resolved ISO dates, never request text.
        String filename = "%s-%s-to-%s.csv".formatted(name, period.from(), period.to());
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }
}
