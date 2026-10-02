package com.oussamaksantini.insightstudio.importing.dto;

/**
 * A problem with the uploaded file.
 *
 * @param line 1-based physical line in the file (the header is line 1); {@code null} for problems
 *     with the file as a whole, such as a file that was already imported or a mapping problem
 * @param field the import field concerned (e.g. {@code list_price}), or {@code null} when the problem
 *     concerns the whole row or file
 * @param column the file's own column header that feeds {@code field} (e.g. {@code Price}), or
 *     {@code null} when no column is concerned (such as a required field that is not mapped)
 */
public record ImportError(Integer line, String field, String column, String message) {

    public static ImportError file(String message) {
        return new ImportError(null, null, null, message);
    }

    /** A problem with the mapping of {@code field}, before any row is read. */
    public static ImportError mapping(String field, String column, String message) {
        return new ImportError(null, field, column, message);
    }

    public static ImportError row(int line, String message) {
        return new ImportError(line, null, null, message);
    }

    /** A problem with one value; the source column is filled in once the mapping is known ({@link #withColumn}). */
    public static ImportError cell(int line, String field, String message) {
        return new ImportError(line, field, null, message);
    }

    public ImportError withColumn(String sourceColumn) {
        return new ImportError(line, field, sourceColumn, message);
    }
}
