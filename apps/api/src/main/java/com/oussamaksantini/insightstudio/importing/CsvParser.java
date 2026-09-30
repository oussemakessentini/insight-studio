package com.oussamaksantini.insightstudio.importing;

import java.util.ArrayList;
import java.util.List;

/**
 * A small RFC 4180 parser: comma-separated fields, optional double quotes around a field, {@code ""}
 * for a literal quote inside a quoted field, and commas or line breaks inside quoted fields.
 * Records end at CRLF, LF or a lone CR. Lines that are completely empty are skipped.
 *
 * <p>Each record carries the 1-based physical line on which it starts, so errors can point at the
 * line a spreadsheet or text editor shows, even when earlier fields span several lines.
 */
final class CsvParser {

    /** One record and the physical line it starts on. */
    record CsvRecord(int line, List<String> fields) {
    }

    /** Malformed quoting that makes the rest of the file impossible to read reliably. */
    static final class CsvSyntaxException extends RuntimeException {

        private final int line;

        CsvSyntaxException(int line, String message) {
            super(message);
            this.line = line;
        }

        int line() {
            return line;
        }
    }

    private CsvParser() {
    }

    /**
     * Parses {@code text} (already decoded, without BOM) into records.
     *
     * @param maxRecords stop after this many records; callers pass one more than they accept to detect overflow
     * @throws CsvSyntaxException for an unterminated quoted field or text after a closing quote
     */
    static List<CsvRecord> parse(String text, int maxRecords) {
        List<CsvRecord> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        int line = 1;
        int recordLine = 1;
        boolean recordHasContent = false;
        int length = text.length();
        int i = 0;

        while (i < length && records.size() < maxRecords) {
            char c = text.charAt(i);
            if (c == '"' && field.isEmpty()) {
                // Quoted field: read until the closing quote.
                int quoteLine = line;
                i++;
                while (true) {
                    if (i >= length) {
                        throw new CsvSyntaxException(quoteLine, "A quoted value is never closed.");
                    }
                    char q = text.charAt(i);
                    if (q == '"') {
                        if (i + 1 < length && text.charAt(i + 1) == '"') {
                            field.append('"');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    if (q == '\r') {
                        // Normalise CRLF (and lone CR) inside a value to LF.
                        field.append('\n');
                        line++;
                        i += (i + 1 < length && text.charAt(i + 1) == '\n') ? 2 : 1;
                        continue;
                    }
                    if (q == '\n') {
                        line++;
                    }
                    field.append(q);
                    i++;
                }
                recordHasContent = true;
                if (i < length) {
                    char next = text.charAt(i);
                    if (next != ',' && next != '\r' && next != '\n') {
                        throw new CsvSyntaxException(line, "Unexpected text after a closing quote.");
                    }
                }
                continue;
            }
            if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                recordHasContent = true;
                i++;
                continue;
            }
            if (c == '\r' || c == '\n') {
                i += (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') ? 2 : 1;
                if (recordHasContent || !field.isEmpty()) {
                    fields.add(field.toString());
                    records.add(new CsvRecord(recordLine, List.copyOf(fields)));
                }
                fields.clear();
                field.setLength(0);
                recordHasContent = false;
                line++;
                recordLine = line;
                continue;
            }
            if (c == '"') {
                throw new CsvSyntaxException(line, "A quote may only appear inside a quoted value (write it as \"\").");
            }
            field.append(c);
            recordHasContent = true;
            i++;
        }
        if (records.size() < maxRecords && (recordHasContent || !field.isEmpty())) {
            fields.add(field.toString());
            records.add(new CsvRecord(recordLine, List.copyOf(fields)));
        }
        return records;
    }
}
