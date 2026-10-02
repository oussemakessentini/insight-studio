package com.oussamaksantini.insightstudio.importing;

import com.oussamaksantini.insightstudio.importing.CsvParser.CsvRecord;
import com.oussamaksantini.insightstudio.importing.ImportService.ParsedFile;
import com.oussamaksantini.insightstudio.importing.dto.ImportError;
import com.oussamaksantini.insightstudio.report.CsvWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The CSV files the import offers for download: a template per kind, and the errors of an upload
 * next to the rows they concern. Both are written with {@link CsvWriter}, so text from an uploaded
 * file can never become a spreadsheet formula.
 */
final class ImportFiles {

    /**
     * Example rows of each template. They fit together: importing the stores template, then the
     * products template, then the sales template into an empty business succeeds.
     */
    private static final Map<ImportKind, List<List<String>>> EXAMPLES = Map.of(
            ImportKind.STORES, List.of(
                    List.of("BOS", "Back Bay", "Boston"),
                    List.of("CAM", "Harvard Square", "Cambridge"),
                    List.of("WEB", "Online store", "")),
            ImportKind.PRODUCTS, List.of(
                    List.of("TEE-001", "Classic tee", "Tops", "24.50"),
                    List.of("JNS-001", "Slim jeans", "Bottoms", "98.00"),
                    List.of("CAP-001", "Wool cap", "Accessories", "19.00")),
            ImportKind.SALES, List.of(
                    List.of("BOS", "R-1001", "2026-09-01T14:30:00-04:00", "TEE-001", "2", "24.50"),
                    List.of("BOS", "R-1001", "2026-09-01T14:30:00-04:00", "JNS-001", "1", "98.00"),
                    List.of("WEB", "R-1002", "2026-09-02T09:15:00", "CAP-001", "1", "17.10")));

    private ImportFiles() {
    }

    /** The kind's header in field order and its example rows. */
    static String template(ImportKind kind) {
        CsvWriter csv = new CsvWriter().header(kind.fieldNames().toArray(String[]::new));
        for (List<String> row : EXAMPLES.get(kind)) {
            row.forEach(csv::text);
            csv.endRow();
        }
        return csv.toString();
    }

    /**
     * One row per error: {@code line}, the row's values under the file's own headers, then
     * {@code field}, {@code column} and {@code error}. Problems with the whole file have no line and
     * no values. A file without errors gives the header row only.
     */
    static String errors(ParsedFile file, List<ImportError> errors) {
        CsvWriter csv = new CsvWriter().literal("line");
        // Header cells come from the upload: written as text, with formula protection.
        file.header().forEach(csv::text);
        csv.literal("field").literal("column").literal("error").endRow();

        Map<Integer, CsvRecord> rowsByLine = new HashMap<>();
        for (CsvRecord row : file.rows()) {
            rowsByLine.put(row.line(), row);
        }
        int columns = file.header().size();
        for (ImportError error : errors) {
            CsvRecord row = error.line() == null ? null : rowsByLine.get(error.line());
            if (error.line() == null) {
                csv.text("");
            } else {
                csv.number(error.line());
            }
            for (int i = 0; i < columns; i++) {
                csv.text(row != null && i < row.fields().size() ? row.fields().get(i) : "");
            }
            csv.text(error.field()).text(error.column()).text(error.message()).endRow();
        }
        return csv.toString();
    }
}
