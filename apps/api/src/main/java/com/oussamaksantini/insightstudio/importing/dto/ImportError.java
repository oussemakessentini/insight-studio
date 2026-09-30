package com.oussamaksantini.insightstudio.importing.dto;

/**
 * A problem with the uploaded file.
 *
 * @param line 1-based physical line in the file (the header is line 1); {@code null} for problems
 *     with the file as a whole, such as a file that was already imported
 * @param column CSV column name, or {@code null} when the problem concerns the whole row or file
 */
public record ImportError(Integer line, String column, String message) {

    public static ImportError file(String message) {
        return new ImportError(null, null, message);
    }

    public static ImportError row(int line, String message) {
        return new ImportError(line, null, message);
    }

    public static ImportError cell(int line, String column, String message) {
        return new ImportError(line, column, message);
    }
}
