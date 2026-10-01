package com.oussamaksantini.insightstudio.report.pdf;

import com.oussamaksantini.insightstudio.report.dto.CategoryReportResponse;
import com.oussamaksantini.insightstudio.report.dto.MonthlyReportResponse;
import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

/**
 * Renders the monthly and category reports as A4 PDFs (docs/saved-reports-contract.md §4) with
 * OpenPDF. Built only from the report responses the JSON endpoints return, plus labels: no query
 * and no calculation of figures happens here, so the PDF always shows the API's numbers.
 *
 * <p>Layout: header, details, summary tiles, the table (header row repeated on every page, rows
 * never split, totals row last), notes, and on every page a footer with the generation time in the
 * business's time zone and "Page X of Y". Text uses the embedded Noto Sans (OFL, see
 * {@code resources/fonts/OFL.txt}), so any business name or currency symbol renders.
 */
@Component
public class ReportPdf {

    static final String NO_SALES = "No sales in this period";

    private static final Color NAVY = new Color(0x0f, 0x1f, 0x3d);
    private static final Color TEXT = new Color(0x1f, 0x2a, 0x44);
    private static final Color MUTED = new Color(0x5b, 0x67, 0x80);
    private static final Color ACCENT = new Color(0x25, 0x63, 0xeb);
    private static final Color RULE = new Color(0xdf, 0xe4, 0xec);
    private static final Color TILE = new Color(0xf5, 0xf7, 0xfb);
    private static final Color HEAD = new Color(0xeb, 0xef, 0xf5);

    private static final float MARGIN = 48;
    private static final float FOOTER_Y = 30;
    private static final float TILE_PADDING = 9;

    private final byte[] regular;
    private final byte[] bold;
    private final Clock clock;

    public ReportPdf(Clock clock) {
        this.clock = clock;
        this.regular = font("fonts/NotoSans-Regular.ttf");
        this.bold = font("fonts/NotoSans-Bold.ttf");
    }

    public byte[] monthly(MonthlyReportResponse report, ReportPdfDetails details) {
        var t = report.totals();
        String currency = details.currency();
        return render(details, report.period(), (doc, f) -> {
            doc.add(tiles(f, List.of(
                    new Tile("Revenue", PdfFormats.money(t.revenue(), currency)),
                    new Tile("Orders", PdfFormats.count(t.orders())),
                    new Tile("Units sold", PdfFormats.count(t.unitsSold())),
                    new Tile("Average order value", PdfFormats.money(t.averageOrderValue(), currency)))));
            doc.add(heading(f, "Revenue by month"));
            if (t.orders() == 0) {
                doc.add(noSales(f));
            } else {
                PdfPTable table = table(new float[] {1.45f, 1.5f, 0.85f, 0.85f, 1.3f, 1.05f, 1.25f});
                headerRow(table, f, "Month", "Revenue", "Orders", "Units", "Avg order value", "Change", "Coverage");
                for (MonthlyReportResponse.Row r : report.rows()) {
                    table.addCell(cell(f.body, PdfFormats.month(r.month()), Element.ALIGN_LEFT));
                    table.addCell(cell(f.body, PdfFormats.money(r.revenue(), currency), Element.ALIGN_RIGHT));
                    table.addCell(cell(f.body, PdfFormats.count(r.orders()), Element.ALIGN_RIGHT));
                    table.addCell(cell(f.body, PdfFormats.count(r.unitsSold()), Element.ALIGN_RIGHT));
                    table.addCell(cell(f.body, PdfFormats.money(r.averageOrderValue(), currency), Element.ALIGN_RIGHT));
                    table.addCell(cell(f.body, PdfFormats.change(r.revenueChangePercent()), Element.ALIGN_RIGHT));
                    table.addCell(coverage(f, r));
                }
                totalsRow(table, f, "Total",
                        PdfFormats.money(t.revenue(), currency),
                        PdfFormats.count(t.orders()),
                        PdfFormats.count(t.unitsSold()),
                        PdfFormats.money(t.averageOrderValue(), currency),
                        "", "");
                doc.add(table);
            }
            doc.add(notes(f, List.of(
                    "Revenue is quantity × unit price at the prices charged at the time of sale.",
                    "An order is a receipt with at least one item.",
                    "Months only partly inside the period are marked “Partial”: they hold fewer days of sales. "
                            + "“Change” compares each month's revenue with the previous row.")));
        });
    }

    public byte[] categories(CategoryReportResponse report, ReportPdfDetails details) {
        var t = report.totals();
        String currency = details.currency();
        return render(details, report.period(), (doc, f) -> {
            doc.add(tiles(f, List.of(
                    new Tile("Revenue", PdfFormats.money(t.revenue(), currency)),
                    new Tile("Orders", PdfFormats.count(t.orders())),
                    new Tile("Units sold", PdfFormats.count(t.unitsSold())),
                    new Tile("Avg order value", PdfFormats.money(t.averageOrderValue(), currency)),
                    new Tile("Avg unit price", PdfFormats.money(t.averageUnitPrice(), currency)))));
            doc.add(heading(f, "Revenue by category"));
            if (t.orders() == 0) {
                doc.add(noSales(f));
            } else {
                PdfPTable table = table(new float[] {3.3f, 1.5f, 0.8f, 0.8f, 0.8f, 1.3f});
                headerRow(table, f, "Category", "Revenue", "Share", "Units", "Orders", "Avg unit price");
                for (CategoryReportResponse.Row r : report.rows()) {
                    table.addCell(cell(f.body, r.category(), Element.ALIGN_LEFT));
                    table.addCell(cell(f.body, PdfFormats.money(r.revenue(), currency), Element.ALIGN_RIGHT));
                    table.addCell(cell(f.body, PdfFormats.percent(r.revenueSharePercent()), Element.ALIGN_RIGHT));
                    table.addCell(cell(f.body, PdfFormats.count(r.unitsSold()), Element.ALIGN_RIGHT));
                    table.addCell(cell(f.body, PdfFormats.count(r.orders()), Element.ALIGN_RIGHT));
                    table.addCell(cell(f.body, PdfFormats.money(r.averageUnitPrice(), currency), Element.ALIGN_RIGHT));
                }
                totalsRow(table, f, "Total",
                        PdfFormats.money(t.revenue(), currency),
                        "",
                        PdfFormats.count(t.unitsSold()),
                        PdfFormats.count(t.orders()),
                        PdfFormats.money(t.averageUnitPrice(), currency));
                doc.add(table);
            }
            doc.add(notes(f, List.of(
                    "Revenue is quantity × unit price at the prices charged at the time of sale.",
                    "An order is a receipt with at least one item.",
                    "A category's orders count the receipts containing at least one of its products. A receipt "
                            + "spanning several categories counts once in each, so category orders can add up to "
                            + "more than the total.")));
        });
    }

    // ---------------------------------------------------------------------------------------------

    /** The fonts of one document (font objects keep per-document state, so they are not shared). */
    private record Fonts(
            Font brand, Font title, Font subtitle, Font label, Font value, Font tileLabel, Font tileValue,
            Font heading, Font tableHead, Font body, Font bodyBold, Font small, Font partial, Font footer) {
    }

    private interface Content {
        void write(Document document, Fonts fonts);
    }

    private record Tile(String label, String value) {
    }

    /**
     * Renders twice: the first pass counts the pages, the second writes "Page X of Y" with the
     * total. The footer takes no space from the content, so both passes lay out identically.
     */
    private byte[] render(ReportPdfDetails details, DateRange period, Content content) {
        String generated = "Generated %s (%s)".formatted(
                PdfFormats.dateTime(LocalDateTime.now(clock.withZone(details.zone()))), details.zone().getId());
        Rendered draft = renderPass(details, period, content, generated, 0);
        return renderPass(details, period, content, generated, draft.pages()).bytes();
    }

    private record Rendered(byte[] bytes, int pages) {
    }

    private Rendered renderPass(ReportPdfDetails details, DateRange period, Content content, String generated, int total) {
        Fonts f = fonts();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, MARGIN, MARGIN, MARGIN, MARGIN + 18);
        PdfWriter writer = PdfWriter.getInstance(document, out);
        Footer footer = new Footer(f, generated, total);
        writer.setPageEvent(footer);
        document.addTitle(details.title() + " – " + details.businessName());
        document.addAuthor(details.businessName());
        document.addCreator("Insight Studio");
        document.open();

        document.add(header(f, details));
        document.add(detailsBlock(f, details, period));
        content.write(document, f);

        document.close();
        return new Rendered(out.toByteArray(), footer.pages);
    }

    private PdfPTable header(Fonts f, ReportPdfDetails details) {
        PdfPTable table = fullWidth(1);
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(RULE);
        cell.setBorderWidthBottom(1f);
        cell.setPadding(0);
        cell.setPaddingBottom(12);
        cell.addElement(new Paragraph("Insight Studio", f.brand));
        Paragraph title = new Paragraph(details.title(), f.title);
        title.setSpacingBefore(4);
        title.setLeading(26);
        cell.addElement(title);
        Paragraph business = new Paragraph(details.businessName(), f.subtitle);
        business.setSpacingBefore(2);
        cell.addElement(business);
        table.addCell(cell);
        table.setSpacingAfter(14);
        return table;
    }

    private PdfPTable detailsBlock(Fonts f, ReportPdfDetails details, DateRange period) {
        PdfPTable table = fullWidth(4);
        setWidths(table, new float[] {0.8f, 2.35f, 0.75f, 1.6f});
        detail(table, f, "Period", PdfFormats.period(period.from(), period.to()));
        detail(table, f, "Store", details.storeLabel());
        detail(table, f, "Time zone", details.zone().getId());
        detail(table, f, "Currency", details.currency());
        if (details.rangeDescription() != null) {
            detail(table, f, "Range", details.rangeDescription());
            detail(table, f, "", "");
        }
        table.setSpacingAfter(14);
        return table;
    }

    private static void detail(PdfPTable table, Fonts f, String label, String value) {
        PdfPCell l = plain(new Phrase(label, f.label));
        l.setPaddingBottom(5);
        PdfPCell v = plain(new Phrase(value, f.value));
        v.setPaddingBottom(5);
        v.setPaddingRight(10);
        table.addCell(l);
        table.addCell(v);
    }

    private static PdfPTable tiles(Fonts f, List<Tile> tiles) {
        PdfPTable table = fullWidth(tiles.size() * 2 - 1);
        float[] widths = new float[tiles.size() * 2 - 1];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = i % 2 == 0 ? 10f : 0.6f;
        }
        setWidths(table, widths);
        // The largest size (at most the default) at which every value fits on one line of its tile.
        float tileWidth = (PageSize.A4.getWidth() - 2 * MARGIN) * 10f / (10f * tiles.size() + 0.6f * (tiles.size() - 1));
        float available = tileWidth - 2 * TILE_PADDING - 2;
        BaseFont base = f.tileValue.getBaseFont();
        float size = f.tileValue.getSize();
        for (Tile tile : tiles) {
            float width = base.getWidthPoint(tile.value(), size);
            if (width > available) {
                size = size * available / width;
            }
        }
        Font valueFont = new Font(base, Math.max(size, 6f), Font.NORMAL, NAVY);
        for (int i = 0; i < tiles.size(); i++) {
            if (i > 0) {
                table.addCell(plain(new Phrase("")));
            }
            PdfPCell cell = new PdfPCell();
            cell.setBorder(Rectangle.BOX);
            cell.setBorderColor(RULE);
            cell.setBorderWidth(0.75f);
            cell.setBackgroundColor(TILE);
            cell.setPadding(TILE_PADDING);
            cell.setPaddingTop(6);
            cell.setPaddingBottom(10);
            cell.addElement(new Paragraph(tiles.get(i).label().toUpperCase(java.util.Locale.ROOT), f.tileLabel));
            Paragraph value = new Paragraph(tiles.get(i).value(), valueFont);
            value.setSpacingBefore(3);
            cell.addElement(value);
            table.addCell(cell);
        }
        table.setSpacingAfter(18);
        return table;
    }

    private static Paragraph heading(Fonts f, String text) {
        Paragraph heading = new Paragraph(text, f.heading);
        heading.setSpacingAfter(6);
        return heading;
    }

    private static PdfPTable noSales(Fonts f) {
        PdfPTable table = fullWidth(1);
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(RULE);
        cell.setBackgroundColor(TILE);
        cell.setPadding(16);
        Paragraph title = new Paragraph(NO_SALES, f.bodyBold);
        title.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(title);
        Paragraph hint = new Paragraph("There are no orders for this period and store, so every figure is zero.", f.small);
        hint.setAlignment(Element.ALIGN_CENTER);
        hint.setSpacingBefore(3);
        cell.addElement(hint);
        table.addCell(cell);
        return table;
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable table = fullWidth(widths.length);
        setWidths(table, widths);
        table.setHeaderRows(1);
        // A row that does not fit moves to the next page whole (rows are never split across pages).
        table.setSplitLate(true);
        table.setSplitRows(false);
        return table;
    }

    private static void headerRow(PdfPTable table, Fonts f, String... labels) {
        for (int i = 0; i < labels.length; i++) {
            PdfPCell cell = cell(f.tableHead, labels[i], i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            cell.setBackgroundColor(HEAD);
            cell.setBorder(Rectangle.BOTTOM);
            cell.setBorderColor(NAVY);
            cell.setBorderWidthBottom(0.75f);
            cell.setPaddingTop(6);
            cell.setPaddingBottom(7);
            table.addCell(cell);
        }
    }

    private static void totalsRow(PdfPTable table, Fonts f, String... values) {
        for (int i = 0; i < values.length; i++) {
            PdfPCell cell = cell(f.bodyBold, values[i], i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            cell.setBackgroundColor(TILE);
            cell.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
            cell.setBorderColor(NAVY);
            cell.setBorderWidthTop(0.9f);
            cell.setBorderWidthBottom(0.9f);
            cell.setPaddingTop(6);
            cell.setPaddingBottom(7);
            table.addCell(cell);
        }
    }

    private static PdfPCell cell(Font font, String text, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(RULE);
        cell.setBorderWidthBottom(0.5f);
        cell.setPaddingLeft(5);
        cell.setPaddingRight(5);
        cell.setPaddingTop(4);
        cell.setPaddingBottom(6);
        return cell;
    }

    private static PdfPCell coverage(Fonts f, MonthlyReportResponse.Row r) {
        Phrase phrase = new Phrase();
        phrase.add(new Chunk("%d of %d days".formatted(r.daysCovered(), r.daysInMonth()), f.body));
        if (!r.complete()) {
            phrase.add(new Chunk("\nPartial", f.partial));
        }
        PdfPCell cell = cell(f.body, "", Element.ALIGN_RIGHT);
        cell.setPhrase(phrase);
        return cell;
    }

    private static PdfPTable notes(Fonts f, List<String> lines) {
        PdfPTable table = fullWidth(2);
        setWidths(table, new float[] {0.25f, 10f});
        PdfPCell title = plain(new Phrase("Notes", f.bodyBold));
        title.setColspan(2);
        title.setPaddingBottom(4);
        table.addCell(title);
        for (String line : lines) {
            PdfPCell bullet = plain(new Phrase("•", f.small));
            bullet.setPaddingBottom(3);
            bullet.setLeading(0, 1.35f);
            PdfPCell text = plain(new Phrase(line, f.small));
            text.setPaddingBottom(3);
            // The default line height is too tight for a note that wraps onto a second line.
            text.setLeading(0, 1.35f);
            table.addCell(bullet);
            table.addCell(text);
        }
        table.setSpacingBefore(18);
        // Keeps the short notes block together on one page.
        table.setKeepTogether(true);
        return table;
    }

    private static PdfPCell plain(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        return cell;
    }

    private static PdfPTable fullWidth(int columns) {
        PdfPTable table = new PdfPTable(columns);
        table.setWidthPercentage(100);
        return table;
    }

    private static void setWidths(PdfPTable table, float[] widths) {
        table.setWidths(widths);
    }

    /** Footer on every page: generation time on the left, "Page X of Y" on the right. */
    private static final class Footer extends PdfPageEventHelper {

        private final Fonts fonts;
        private final String generated;
        private final int total;
        private int pages;

        Footer(Fonts fonts, String generated, int total) {
            this.fonts = fonts;
            this.generated = generated;
            this.total = total;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            pages = writer.getPageNumber();
            PdfContentByte canvas = writer.getDirectContent();
            float left = document.left();
            float right = document.right();
            canvas.saveState();
            canvas.setColorStroke(RULE);
            canvas.setLineWidth(0.5f);
            canvas.moveTo(left, FOOTER_Y + 14);
            canvas.lineTo(right, FOOTER_Y + 14);
            canvas.stroke();
            canvas.restoreState();

            ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, new Phrase(generated, fonts.footer), left, FOOTER_Y, 0);
            String page = "Page %d of %d".formatted(pages, Math.max(total, pages));
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, new Phrase(page, fonts.footer), right, FOOTER_Y, 0);
        }
    }

    private Fonts fonts() {
        BaseFont r = baseFont("NotoSans-Regular.ttf", regular);
        BaseFont b = baseFont("NotoSans-Bold.ttf", bold);
        return new Fonts(
                new Font(b, 9.5f, Font.NORMAL, ACCENT),
                new Font(b, 20, Font.NORMAL, NAVY),
                new Font(r, 11, Font.NORMAL, MUTED),
                new Font(r, 8.5f, Font.NORMAL, MUTED),
                new Font(r, 9.5f, Font.NORMAL, TEXT),
                new Font(b, 7, Font.NORMAL, MUTED),
                new Font(b, 13, Font.NORMAL, NAVY),
                new Font(b, 11, Font.NORMAL, NAVY),
                new Font(b, 8.5f, Font.NORMAL, NAVY),
                new Font(r, 9, Font.NORMAL, TEXT),
                new Font(b, 9, Font.NORMAL, NAVY),
                new Font(r, 8, Font.NORMAL, MUTED),
                new Font(b, 7, Font.NORMAL, ACCENT),
                new Font(r, 7.5f, Font.NORMAL, MUTED));
    }

    private static BaseFont baseFont(String name, byte[] bytes) {
        try {
            return BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, false, bytes, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] font(String path) {
        try (InputStream in = ReportPdf.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing font " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
