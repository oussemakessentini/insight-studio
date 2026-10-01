package com.oussamaksantini.insightstudio.report.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.oussamaksantini.insightstudio.report.dto.CategoryReportResponse;
import com.oussamaksantini.insightstudio.report.dto.MonthlyReportResponse;
import com.oussamaksantini.insightstudio.reporting.DateRange;
import com.oussamaksantini.insightstudio.testsupport.PdfText;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The PDF layout, from hand-built report responses (no database). Also writes the samples looked at
 * when changing the layout to {@code target/pdf-samples/} (render them with
 * {@code pdftoppm -r 80 -png file.pdf prefix}).
 */
class ReportPdfTest {

    private static final Path SAMPLES = Path.of("target", "pdf-samples");
    /** 1 Oct 2026 09:05 UTC = 11:05 in Paris. */
    private final ReportPdf pdf = new ReportPdf(Clock.fixed(Instant.parse("2026-10-01T09:05:00Z"), ZoneOffset.UTC));

    private static final ReportPdfDetails PARIS = new ReportPdfDetails(
            "Q3 by month", "Fieldstone Apparel Co.", "EUR", ZoneId.of("Europe/Paris"), "Boston (BOS)",
            "Previous quarter (relative)");

    private static String text(byte[] bytes) {
        return PdfText.text(bytes);
    }

    private static List<String> pages(byte[] bytes) {
        return PdfText.pages(bytes);
    }

    private static void sample(String name, byte[] bytes) throws IOException {
        Files.createDirectories(SAMPLES);
        Files.write(SAMPLES.resolve(name), bytes);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    @Test
    void monthlyReportWithData() throws IOException {
        var rows = List.of(
                new MonthlyReportResponse.Row(LocalDate.parse("2026-07-01"), money("12450.50"), 312, 701,
                        money("39.91"), null, 31, 31, true),
                new MonthlyReportResponse.Row(LocalDate.parse("2026-08-01"), money("13980.00"), 340, 760,
                        money("41.12"), new BigDecimal("12.3"), 31, 31, true),
                new MonthlyReportResponse.Row(LocalDate.parse("2026-09-01"), money("6120.25"), 150, 330,
                        money("40.80"), new BigDecimal("-56.2"), 14, 30, false));
        var report = new MonthlyReportResponse(
                new DateRange(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-09-14")), 3L, rows,
                new MonthlyReportResponse.Totals(money("32550.75"), 802, 1791, money("40.59")));

        byte[] bytes = pdf.monthly(report, PARIS);
        sample("monthly.pdf", bytes);

        String text = text(bytes);
        assertThat(text)
                .contains("Insight Studio", "Q3 by month", "Fieldstone Apparel Co.")
                .contains("1 Jul 2026 – 14 Sep 2026 (76 days)", "Europe/Paris", "Boston (BOS)", "EUR",
                        "Previous quarter (relative)")
                .contains("July 2026", "€12,450.50", "+12.3%", "−56.2%", "Partial", "14 of 30 days")
                .contains("Total €32,550.75 802 1,791 €40.59")
                .contains("Generated 1 Oct 2026, 11:05 (Europe/Paris)", "Page 1 of 1")
                .doesNotContain(ReportPdf.NO_SALES);
        assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void longCategoryReportRepeatsTheHeaderOnEveryPage() throws IOException {
        List<CategoryReportResponse.Row> rows = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            String name = i % 17 == 0
                    ? "Category %03d with a deliberately long name that has to wrap inside its cell".formatted(i)
                    : "Category %03d".formatted(i);
            rows.add(new CategoryReportResponse.Row(name, money("%d.00".formatted(1000 - i)), 10, 5,
                    new BigDecimal("0.8"), money("%d.00".formatted(100 - i / 2))));
        }
        var report = new CategoryReportResponse(
                new DateRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-09-30")), null, rows,
                new CategoryReportResponse.Totals(money("112740.00"), 1200, 480, money("234.88"), money("93.95")));
        ReportPdfDetails details = new ReportPdfDetails("Category report", "Ünïcødé Ltd — Zürich", "CHF",
                ZoneId.of("Pacific/Auckland"), ReportPdfDetails.ALL_STORES, null);

        byte[] bytes = pdf.categories(report, details);
        sample("categories-long.pdf", bytes);

        List<String> pages = pages(bytes);
        assertThat(pages).hasSizeGreaterThan(2);
        for (int i = 0; i < pages.size(); i++) {
            assertThat(pages.get(i)).as("page %d", i + 1)
                    .contains("Category Revenue Share Units Orders Avg unit price")
                    .contains("Page %d of %d".formatted(i + 1, pages.size()))
                    .contains("Generated 1 Oct 2026, 22:05 (Pacific/Auckland)");
        }
        String all = String.join("\n", pages);
        assertThat(all).contains("Ünïcødé Ltd — Zürich", "All stores", "CHF", "Category 001", "Category 120")
                .contains("Total CHF 112,740.00 1,200 480 CHF 93.95");
        assertThat(pages.getLast()).contains("Notes");
    }

    @Test
    void emptyReportSaysThereAreNoSales() throws IOException {
        var report = new MonthlyReportResponse(
                new DateRange(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30")), null,
                List.of(new MonthlyReportResponse.Row(LocalDate.parse("2026-09-01"), money("0.00"), 0, 0,
                        money("0.00"), null, 30, 30, true)),
                new MonthlyReportResponse.Totals(money("0.00"), 0, 0, money("0.00")));
        ReportPdfDetails details = new ReportPdfDetails("Monthly report", "Empty Co", "USD",
                ZoneId.of("America/Los_Angeles"), ReportPdfDetails.ALL_STORES, null);

        byte[] bytes = pdf.monthly(report, details);
        sample("monthly-empty.pdf", bytes);

        assertThat(text(bytes)).contains(ReportPdf.NO_SALES, "$0.00", "Page 1 of 1", "1 Sep 2026 – 30 Sep 2026 (30 days)")
                .doesNotContain("Range");
        var categories = new CategoryReportResponse(report.period(), null, List.of(),
                new CategoryReportResponse.Totals(money("0.00"), 0, 0, money("0.00"), money("0.00")));
        assertThat(text(pdf.categories(categories, details))).contains(ReportPdf.NO_SALES);
    }

    @Test
    void formats() {
        assertThat(PdfFormats.money(money("1234.5"), "EUR")).isEqualTo("€1,234.50");
        assertThat(PdfFormats.money(money("1234.5"), "USD")).isEqualTo("$1,234.50");
        assertThat(PdfFormats.money(money("1234.5"), "JPY")).isEqualTo("¥1,234.50");
        assertThat(PdfFormats.money(money("1234.5"), "CHF")).isEqualTo("CHF 1,234.50");
        assertThat(PdfFormats.money(money("0"), "GBP")).isEqualTo("£0.00");
        assertThat(PdfFormats.change(null)).isEqualTo("—");
        assertThat(PdfFormats.change(new BigDecimal("0.0"))).isEqualTo("0.0%");
        assertThat(PdfFormats.period(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-01")))
                .isEqualTo("1 Jul 2026 – 1 Jul 2026 (1 day)");
    }
}
