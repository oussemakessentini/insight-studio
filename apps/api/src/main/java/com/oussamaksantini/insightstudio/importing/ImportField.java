package com.oussamaksantini.insightstudio.importing;

import java.util.List;

/**
 * A field an import kind reads from the file, in the template's column order.
 *
 * @param name the field name, also the template's column header
 * @param synonyms other headers that suggest this field, already {@linkplain ColumnMapping#normalize normalised}
 */
record ImportField(String name, String label, boolean required, String description, List<String> synonyms) {

    static ImportField required(String name, String label, String description, String... synonyms) {
        return new ImportField(name, label, true, description, List.of(synonyms));
    }

    static ImportField optional(String name, String label, String description, String... synonyms) {
        return new ImportField(name, label, false, description, List.of(synonyms));
    }
}
