package com.oussamaksantini.insightstudio.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.oussamaksantini.insightstudio.importing.CsvParser.CsvRecord;
import com.oussamaksantini.insightstudio.importing.ColumnMapping.Resolution;
import com.oussamaksantini.insightstudio.importing.dto.ImportError;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Mapping checks, suggestions and the rearranging of rows, without a database. */
class ColumnMappingTest {

    private static Map<String, String> mapping(String... fieldsAndColumns) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (int i = 0; i < fieldsAndColumns.length; i += 2) {
            mapping.put(fieldsAndColumns[i], fieldsAndColumns[i + 1]);
        }
        return mapping;
    }

    // ---------------------------------------------------------------- suggestions

    @Test
    void suggestsFieldsFromSynonymsIgnoringCaseSpacesAndPunctuation() {
        assertThat(ColumnMapping.suggest(ImportKind.STORES, List.of("Notes", "Store ID", "STORE  name", "Town")))
                .containsExactly(Map.entry("code", "Store ID"), Map.entry("name", "STORE  name"), Map.entry("city", "Town"));
        assertThat(ColumnMapping.suggest(ImportKind.PRODUCTS, List.of("Product Code", "Title", "Department", "Price")))
                .containsExactly(Map.entry("sku", "Product Code"), Map.entry("name", "Title"),
                        Map.entry("category", "Department"), Map.entry("list_price", "Price"));
        assertThat(ColumnMapping.suggest(ImportKind.SALES, List.of("Shop", "Receipt #", "Date", "Item", "Qty", "Price")))
                .containsExactly(Map.entry("store_code", "Shop"), Map.entry("receipt_number", "Receipt #"),
                        Map.entry("sold_at", "Date"), Map.entry("sku", "Item"), Map.entry("quantity", "Qty"),
                        Map.entry("unit_price", "Price"));
    }

    @Test
    void anExactFieldNameWinsOverASynonymAndEachColumnIsSuggestedOnce() {
        // "code" is a synonym of sku, but the file also has a "SKU" column; "Name" feeds only name.
        Map<String, String> suggested = ColumnMapping.suggest(ImportKind.PRODUCTS, List.of("code", "Name", "SKU", "list-price"));
        assertThat(suggested).containsEntry("sku", "SKU").containsEntry("name", "Name")
                .containsEntry("list_price", "list-price").containsEntry("category", null);
        assertThat(suggested.keySet()).containsExactly("sku", "name", "category", "list_price");
    }

    @Test
    void unrecognisedColumnsSuggestNothing() {
        Map<String, String> suggested = ColumnMapping.suggest(ImportKind.STORES, List.of("a", "b"));
        assertThat(new HashMap<>(suggested)).containsOnlyKeys("code", "name", "city").doesNotContainValue("a");
        assertThat(suggested.values()).containsOnlyNulls();
    }

    // ---------------------------------------------------------------- checking a mapping

    @Test
    void identityMappingMatchesFieldNamesIgnoringCase() {
        Resolution resolution = ColumnMapping.resolve(ImportKind.STORES, List.of("Extra", "NAME", "Code"), null);
        assertThat(resolution.errors()).isEmpty();
        assertThat(resolution.mapping().column("code")).isEqualTo("Code");
        assertThat(resolution.mapping().column("name")).isEqualTo("NAME");
        // The optional city has no column: not mapped, which is not an error.
        assertThat(resolution.mapping().isMapped("city")).isFalse();
    }

    @Test
    void identityMappingReportsMissingRequiredColumns() {
        Resolution resolution = ColumnMapping.resolve(ImportKind.PRODUCTS, List.of("sku", "name"), null);
        assertThat(resolution.errors()).extracting(ImportError::line, ImportError::field, ImportError::column)
                .containsExactly(tuple(null, "category", null), tuple(null, "list_price", null));
        assertThat(resolution.errors().getFirst().message())
                .isEqualTo("Required field 'category' (Category) is not mapped: the file has no column named 'category'.");
    }

    @Test
    void explicitMappingProblemsAreReportedTogether() {
        Resolution resolution = ColumnMapping.resolve(ImportKind.STORES, List.of("Store ID", "Label", "Town"),
                mapping("code", "Store ID", "name", "Store ID", "city", "Ville", "colour", "Label"));
        assertThat(resolution.errors()).extracting(ImportError::field, ImportError::column, ImportError::message)
                .containsExactly(
                        tuple(null, null, "Unknown field 'colour' in the mapping; stores imports have the fields code, name, city."),
                        tuple("name", "Store ID", "Column 'Store ID' is already mapped to 'code'; a column can feed only one field."),
                        tuple("city", "Ville", "Column 'Ville' (mapped to 'city') is not in the file."));
    }

    @Test
    void requiredFieldsMustBeMappedExplicitly() {
        Resolution resolution = ColumnMapping.resolve(ImportKind.STORES, List.of("code", "name"), mapping("code", "code", "name", null));
        assertThat(resolution.errors()).singleElement().satisfies(e -> {
            assertThat(e.field()).isEqualTo("name");
            assertThat(e.column()).isNull();
            assertThat(e.message()).isEqualTo("Required field 'name' (Store name) is not mapped to a column.");
        });
    }

    @Test
    void explicitMappingUsesExactTrimmedHeaders() {
        List<String> header = List.of("Store ID", "Name");
        assertThat(ColumnMapping.resolve(ImportKind.STORES, header, mapping("code", " Store ID ", "name", "Name")).errors()).isEmpty();
        assertThat(ColumnMapping.resolve(ImportKind.STORES, header, mapping("code", "store id", "name", "Name")).errors())
                .singleElement().extracting(ImportError::message).isEqualTo("Column 'store id' (mapped to 'code') is not in the file.");
    }

    @Test
    void aRepeatedHeaderCannotBeMapped() {
        Resolution resolution = ColumnMapping.resolve(ImportKind.STORES, List.of("code", "name", "name"), null);
        assertThat(resolution.errors()).singleElement().satisfies(e -> {
            assertThat(e.field()).isEqualTo("name");
            assertThat(e.message()).contains("appears 2 times");
        });
    }

    // ---------------------------------------------------------------- applying a mapping

    @Test
    void rearrangesRowsIntoFieldOrderAndRejectsRowsOfTheWrongWidth() {
        List<String> header = List.of("Town", "Notes", "Name", "ID");
        ColumnMapping mapping = ColumnMapping.resolve(ImportKind.STORES, header,
                mapping("code", "ID", "name", "Name", "city", "Town")).mapping();
        List<ImportError> errors = new ArrayList<>();
        List<CsvRecord> rows = mapping.apply(List.of(
                new CsvRecord(2, List.of("Boston", "ignored", "Back Bay", "BOS")),
                new CsvRecord(3, List.of("Cambridge", "too short"))), errors);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.line()).isEqualTo(2);
            assertThat(r.fields()).containsExactly("BOS", "Back Bay", "Boston");
        });
        assertThat(errors).singleElement().satisfies(e -> {
            assertThat(e.line()).isEqualTo(3);
            assertThat(e.message()).isEqualTo("Expected 4 values but found 2.");
        });
        assertThat(mapping.withColumn(ImportError.cell(2, "code", "x")).column()).isEqualTo("ID");
    }

    @Test
    void unmappedOptionalFieldsReadAsEmpty() {
        ColumnMapping mapping = ColumnMapping.resolve(ImportKind.STORES, List.of("code", "name"), null).mapping();
        assertThat(mapping.apply(List.of(new CsvRecord(2, List.of("BOS", "Back Bay"))), new ArrayList<>()).getFirst().fields())
                .containsExactly("BOS", "Back Bay", "");
    }
}
