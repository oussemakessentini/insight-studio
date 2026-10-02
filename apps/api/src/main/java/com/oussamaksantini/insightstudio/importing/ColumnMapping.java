package com.oussamaksantini.insightstudio.importing;

import com.oussamaksantini.insightstudio.importing.CsvParser.CsvRecord;
import com.oussamaksantini.insightstudio.importing.dto.ImportError;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which column of a file feeds each field of an import kind (docs/catalog-imports-contract.md §3).
 * A mapping names columns by their header text; the header cells are compared trimmed. Columns that
 * feed no field are ignored.
 */
final class ColumnMapping {

    private final ImportKind kind;
    private final List<String> header;
    /** Column index per mapped field; unmapped fields are absent. */
    private final Map<String, Integer> columnByField;

    private ColumnMapping(ImportKind kind, List<String> header, Map<String, Integer> columnByField) {
        this.kind = kind;
        this.header = header;
        this.columnByField = columnByField;
    }

    /**
     * A mapping and the problems with it. When {@code errors} is not empty the mapping must not be
     * applied: the file is rejected before any row is read.
     */
    record Resolution(ColumnMapping mapping, List<ImportError> errors) {
    }

    /**
     * Checks a requested mapping against the file's header.
     *
     * @param header the header cells, trimmed
     * @param requested field to column header ({@code null} value: not mapped), or {@code null} for the
     *     identity mapping: each field reads the column of the same name (ignoring case), if there is one
     */
    static Resolution resolve(ImportKind kind, List<String> header, Map<String, String> requested) {
        List<ImportError> errors = new ArrayList<>();
        Map<String, Integer> columns = new LinkedHashMap<>();

        if (requested != null) {
            for (String name : requested.keySet()) {
                if (kind.field(name).isEmpty()) {
                    errors.add(ImportError.mapping(null, null, "Unknown field '%s' in the mapping; %s imports have the fields %s."
                            .formatted(name, kind.param(), String.join(", ", kind.fieldNames()))));
                }
            }
        }
        Map<Integer, String> fieldByColumn = new LinkedHashMap<>();
        for (ImportField field : kind.fields()) {
            String wanted = requested == null ? field.name() : blankToNull(requested.get(field.name()));
            if (wanted == null) {
                if (field.required()) {
                    errors.add(ImportError.mapping(field.name(), null,
                            "Required field '%s' (%s) is not mapped to a column.".formatted(field.name(), field.label())));
                }
                continue;
            }
            List<Integer> matches = matches(header, wanted.strip(), requested == null);
            if (matches.isEmpty()) {
                if (requested != null) {
                    errors.add(ImportError.mapping(field.name(), wanted,
                            "Column '%s' (mapped to '%s') is not in the file.".formatted(wanted, field.name())));
                } else if (field.required()) {
                    errors.add(ImportError.mapping(field.name(), null,
                            "Required field '%s' (%s) is not mapped: the file has no column named '%s'."
                                    .formatted(field.name(), field.label(), field.name())));
                }
                continue;
            }
            int column = matches.getFirst();
            String columnName = header.get(column);
            if (matches.size() > 1) {
                errors.add(ImportError.mapping(field.name(), columnName,
                        "Column '%s' appears %d times in the header; rename the copies so it can be mapped."
                                .formatted(columnName, matches.size())));
                continue;
            }
            String other = fieldByColumn.putIfAbsent(column, field.name());
            if (other != null) {
                errors.add(ImportError.mapping(field.name(), columnName,
                        "Column '%s' is already mapped to '%s'; a column can feed only one field."
                                .formatted(columnName, other)));
                continue;
            }
            columns.put(field.name(), column);
        }
        return new Resolution(new ColumnMapping(kind, header, columns), errors);
    }

    private static List<Integer> matches(List<String> header, String wanted, boolean ignoreCase) {
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String cell = header.get(i);
            if (ignoreCase ? cell.equalsIgnoreCase(wanted) : cell.equals(wanted)) {
                matches.add(i);
            }
        }
        return matches;
    }

    boolean isMapped(String field) {
        return columnByField.containsKey(field);
    }

    /** The file's header of the column feeding {@code field}, or {@code null} when it is not mapped. */
    String column(String field) {
        Integer index = field == null ? null : columnByField.get(field);
        return index == null ? null : header.get(index);
    }

    /** Adds the source column to errors that name a field. */
    ImportError withColumn(ImportError error) {
        String column = column(error.field());
        return column == null ? error : error.withColumn(column);
    }

    /**
     * Rearranges each row into the kind's field order, with {@code ""} for unmapped fields. A row
     * with more or fewer values than the header is an error and is left out.
     */
    List<CsvRecord> apply(List<CsvRecord> rows, List<ImportError> errors) {
        List<ImportField> fields = kind.fields();
        List<CsvRecord> mapped = new ArrayList<>(rows.size());
        for (CsvRecord row : rows) {
            if (row.fields().size() != header.size()) {
                errors.add(ImportError.row(row.line(),
                        "Expected %d values but found %d.".formatted(header.size(), row.fields().size())));
                continue;
            }
            List<String> values = new ArrayList<>(fields.size());
            for (ImportField field : fields) {
                Integer index = columnByField.get(field.name());
                values.add(index == null ? "" : row.fields().get(index));
            }
            mapped.add(new CsvRecord(row.line(), List.copyOf(values)));
        }
        return mapped;
    }

    /**
     * A column for each field when one is recognisable: first a header equal to the field name, then
     * one equal to a synonym, both {@linkplain #normalize normalised}. A column is suggested at most once.
     */
    static Map<String, String> suggest(ImportKind kind, List<String> header) {
        Map<String, String> suggested = new LinkedHashMap<>();
        kind.fields().forEach(f -> suggested.put(f.name(), null));
        Set<Integer> used = new HashSet<>();
        for (ImportField field : kind.fields()) {
            pick(header, Set.of(normalize(field.name())), used).ifPresent(i -> suggested.put(field.name(), header.get(i)));
        }
        for (ImportField field : kind.fields()) {
            if (suggested.get(field.name()) == null) {
                Set<String> synonyms = field.synonyms().stream().collect(Collectors.toSet());
                pick(header, synonyms, used).ifPresent(i -> suggested.put(field.name(), header.get(i)));
            }
        }
        return suggested;
    }

    private static Optional<Integer> pick(List<String> header, Set<String> names, Set<Integer> used) {
        for (int i = 0; i < header.size(); i++) {
            if (!used.contains(i) && names.contains(normalize(header.get(i)))) {
                used.add(i);
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    /** Lower case letters and digits only: "Store ID", "store_id" and "STORE-ID" all become "storeid". */
    static String normalize(String header) {
        StringBuilder out = new StringBuilder();
        header.toLowerCase(Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
