package com.oussamaksantini.insightstudio.importing.dto;

import com.oussamaksantini.insightstudio.importing.ImportKind;
import java.util.List;
import java.util.Map;

/**
 * The start of a file and how its columns could feed the import's fields. Nothing is validated
 * beyond reading the file.
 *
 * @param rowCount data rows in the file (the header excluded), counted up to one more than the limit
 * @param columns the header cells, trimmed, in file order: the values a mapping refers to
 * @param sampleRows the first data rows, each cell as written (rows may have more or fewer cells than the header)
 * @param fields the kind's fields in template order
 * @param suggestedMapping every field, in template order, with the column suggested for it or {@code null}
 */
public record ImportPreviewResponse(
        ImportKind kind,
        String fileName,
        int rowCount,
        List<String> columns,
        List<List<String>> sampleRows,
        List<Field> fields,
        Map<String, String> suggestedMapping) {

    public record Field(String name, String label, boolean required, String description) {
    }
}
