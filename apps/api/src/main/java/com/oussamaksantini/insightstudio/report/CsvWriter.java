package com.oussamaksantini.insightstudio.report;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds RFC 4180 CSV: comma-separated, CRLF line endings, and fields containing a comma, quote,
 * CR or LF enclosed in double quotes with embedded quotes doubled.
 *
 * <p>Text cells are also protected against CSV (formula) injection: spreadsheet applications
 * evaluate a cell starting with {@code = + - @}, tab or CR as a formula, so such text gets a
 * leading single quote. Numbers are written with {@link #number} and never altered, so negative
 * values stay numeric.
 */
public final class CsvWriter {

    private static final String LINE_END = "\r\n";

    private final StringBuilder out = new StringBuilder();
    private final List<String> row = new ArrayList<>();

    /** Adds a text cell from the data (e.g. a category name) with formula-injection protection. */
    public CsvWriter text(String value) {
        row.add(quote(neutralizeFormula(value == null ? "" : value)));
        return this;
    }

    /** Adds a plain number: {@code .} as decimal separator, no grouping or currency; empty for {@code null}. */
    public CsvWriter number(BigDecimal value) {
        row.add(value == null ? "" : value.toPlainString());
        return this;
    }

    public CsvWriter number(long value) {
        row.add(Long.toString(value));
        return this;
    }

    /** Adds a trusted literal such as a header name, an ISO date or a boolean. */
    public CsvWriter literal(Object value) {
        row.add(quote(String.valueOf(value)));
        return this;
    }

    public CsvWriter header(String... names) {
        for (String name : names) {
            literal(name);
        }
        return endRow();
    }

    public CsvWriter endRow() {
        out.append(String.join(",", row)).append(LINE_END);
        row.clear();
        return this;
    }

    @Override
    public String toString() {
        return out.toString();
    }

    static String neutralizeFormula(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        boolean risky = first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
        return risky ? "'" + value : value;
    }

    static String quote(String value) {
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
        return needsQuotes ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
