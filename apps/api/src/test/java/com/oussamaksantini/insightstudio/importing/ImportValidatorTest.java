package com.oussamaksantini.insightstudio.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.oussamaksantini.insightstudio.importing.CsvParser.CsvRecord;
import com.oussamaksantini.insightstudio.importing.ImportValidator.Catalog;
import com.oussamaksantini.insightstudio.importing.ImportValidator.PlannedReceipt;
import com.oussamaksantini.insightstudio.importing.ImportValidator.ReceiptKey;
import com.oussamaksantini.insightstudio.importing.ImportValidator.Validation;
import com.oussamaksantini.insightstudio.importing.dto.ImportError;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Row rules, independent of the database. Business zone: America/New_York (UTC-4 in September). */
class ImportValidatorTest {

    private static final String HEADER = "store_code,receipt_number,sold_at,sku,quantity,unit_price\n";
    private static final Catalog CATALOG = new Catalog(
            Map.of("BOS", 1L, "WEB", 2L), Map.of("TEE-1", 10L, "JNS-1", 11L), ZoneId.of("America/New_York"));

    private static Validation validate(String dataRows) {
        return validate(dataRows, Set.of());
    }

    private static Validation validate(String dataRows, Set<ReceiptKey> existing) {
        List<CsvRecord> records = CsvParser.parse(HEADER + dataRows, Integer.MAX_VALUE);
        return ImportValidator.validate(records.subList(1, records.size()), CATALOG,
                keys -> keys.stream().filter(existing::contains).collect(java.util.stream.Collectors.toSet()));
    }

    private static List<ImportError> errors(String dataRows) {
        return validate(dataRows).errors();
    }

    @Test
    void headerIsCaseInsensitiveAndTrimmed() {
        assertThat(ImportValidator.isValidHeader(
                List.of(" Store_Code", "RECEIPT_NUMBER ", "sold_at", "sku", "quantity", "unit_price"))).isTrue();
        assertThat(ImportValidator.isValidHeader(
                List.of("receipt_number", "store_code", "sold_at", "sku", "quantity", "unit_price"))).isFalse();
        assertThat(ImportValidator.isValidHeader(
                List.of("store_code", "receipt_number", "sold_at", "sku", "quantity"))).isFalse();
    }

    @Test
    void groupsRowsIntoReceiptsAndTotals() {
        Validation v = validate("""
                BOS,R-1,2026-09-01T14:30:00-04:00,TEE-1,2,24.50
                BOS,R-1,2026-09-01T18:30:00Z,JNS-1,1,98
                WEB,R-1,2026-09-02T10:00:00,TEE-1,3,20.00
                """);
        assertThat(v.errors()).isEmpty();
        assertThat(v.rowCount()).isEqualTo(3);
        assertThat(v.receipts()).hasSize(2);
        assertThat(v.lineCount()).isEqualTo(3);
        assertThat(v.totalAmount()).isEqualByComparingTo("207.00");

        PlannedReceipt first = v.receipts().getFirst();
        assertThat(first.key()).isEqualTo(new ReceiptKey(1L, "R-1"));
        assertThat(first.soldAt()).isEqualTo(OffsetDateTime.parse("2026-09-01T18:30:00Z"));
        assertThat(first.lines()).extracting(l -> l.productId(), l -> l.quantity(), l -> l.unitPrice())
                .containsExactly(tuple(10L, 2, new BigDecimal("24.50")), tuple(11L, 1, new BigDecimal("98.00")));
    }

    @Test
    void localTimesUseTheBusinessTimeZone() {
        Validation v = validate("WEB,R-1,2026-09-02T10:00:00,TEE-1,1,1.00\nWEB,R-2,2026-01-15T10:00,TEE-1,1,1.00\n");
        assertThat(v.errors()).isEmpty();
        // EDT (UTC-4) in September, EST (UTC-5) in January.
        assertThat(v.receipts()).extracting(PlannedReceipt::soldAt).containsExactly(
                OffsetDateTime.parse("2026-09-02T14:00:00Z"), OffsetDateTime.parse("2026-01-15T15:00:00Z"));
    }

    @Test
    void localTimeSkippedByDaylightSavingIsAnError() {
        assertThat(errors("BOS,R-1,2026-03-08T02:30:00,TEE-1,1,1.00\n"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.line()).isEqualTo(2);
                    assertThat(e.column()).isEqualTo("sold_at");
                    assertThat(e.message()).contains("does not exist");
                });
    }

    @Test
    void trimsValues() {
        Validation v = validate(" BOS , R-1 , 2026-09-01T14:30:00Z , TEE-1 , 2 , 3.10 \n");
        assertThat(v.errors()).isEmpty();
        assertThat(v.receipts().getFirst().key()).isEqualTo(new ReceiptKey(1L, "R-1"));
    }

    @Test
    void reportsEachInvalidCellWithLineAndColumn() {
        List<ImportError> errors = errors("""
                XXX,,yesterday,NOPE,0,-1
                BOS,R-2,2026-09-01T10:00:00Z,TEE-1,1.5,1.999
                BOS,R-3,2026-09-01,TEE-1,abc,1e3
                """);
        assertThat(errors).extracting(ImportError::line, ImportError::column).containsExactly(
                tuple(2, "store_code"), tuple(2, "receipt_number"), tuple(2, "sold_at"), tuple(2, "sku"),
                tuple(2, "quantity"), tuple(2, "unit_price"),
                tuple(3, "quantity"), tuple(3, "unit_price"),
                tuple(4, "sold_at"), tuple(4, "quantity"), tuple(4, "unit_price"));
        assertThat(errors.get(0).message()).isEqualTo("Unknown store code 'XXX'.");
        assertThat(errors.get(1).message()).isEqualTo("A value is required.");
    }

    @Test
    void storeCodesAndSkusAreCaseSensitive() {
        assertThat(errors("bos,R-1,2026-09-01T10:00:00Z,tee-1,1,1.00\n"))
                .extracting(ImportError::column).containsExactly("store_code", "sku");
    }

    @Test
    void wrongNumberOfValuesIsARowError() {
        assertThat(errors("BOS,R-1,2026-09-01T10:00:00Z,TEE-1,1\nBOS,R-2,2026-09-01T10:00:00Z,TEE-1,1,1.00,extra\n"))
                .extracting(ImportError::line, ImportError::column, ImportError::message)
                .containsExactly(
                        tuple(2, null, "Expected 6 values but found 5."),
                        tuple(3, null, "Expected 6 values but found 7."));
    }

    @Test
    void receiptNumberLength() {
        String forty = "R".repeat(40);
        assertThat(errors("BOS," + forty + ",2026-09-01T10:00:00Z,TEE-1,1,1.00\n")).isEmpty();
        assertThat(errors("BOS," + forty + "1,2026-09-01T10:00:00Z,TEE-1,1,1.00\n"))
                .singleElement().extracting(ImportError::column).isEqualTo("receipt_number");
    }

    @Test
    void priceAndQuantityBoundaries() {
        assertThat(errors("BOS,R-1,2026-09-01T10:00:00Z,TEE-1,1,0\nBOS,R-2,2026-09-01T10:00:00Z,TEE-1,999999999,0.5\n"))
                .isEmpty();
        assertThat(errors("BOS,R-1,2026-09-01T10:00:00Z,TEE-1,+1,.50\n"))
                .extracting(ImportError::column).containsExactly("quantity", "unit_price");
    }

    @Test
    void rowsOfOneReceiptMustShareSoldAt() {
        List<ImportError> errors = errors("""
                BOS,R-1,2026-09-01T14:30:00-04:00,TEE-1,1,1.00
                BOS,R-1,2026-09-01T18:30:00Z,JNS-1,1,1.00
                WEB,R-9,2026-09-01T10:00:00Z,TEE-1,1,1.00
                BOS,R-1,2026-09-01T14:31:00-04:00,TEE-1,1,1.00
                """);
        assertThat(errors).extracting(ImportError::line, ImportError::column).contains(tuple(5, "sold_at"));
        assertThat(errors.stream().filter(e -> "sold_at".equals(e.column())).findFirst().orElseThrow().message())
                .contains("line 2");
    }

    @Test
    void sameSkuTwiceInAReceiptIsAnError() {
        List<ImportError> errors = errors("""
                BOS,R-1,2026-09-01T10:00:00Z,TEE-1,1,1.00
                BOS,R-2,2026-09-01T10:00:00Z,TEE-1,1,1.00
                BOS,R-1,2026-09-01T10:00:00Z,TEE-1,2,1.00
                """);
        assertThat(errors).singleElement().satisfies(e -> {
            assertThat(e.line()).isEqualTo(4);
            assertThat(e.column()).isEqualTo("sku");
            assertThat(e.message()).contains("line 2");
        });
    }

    @Test
    void sameReceiptNumberInDifferentStoresIsFine() {
        Validation v = validate("BOS,R-1,2026-09-01T10:00:00Z,TEE-1,1,1.00\nWEB,R-1,2026-09-02T10:00:00Z,TEE-1,1,1.00\n");
        assertThat(v.errors()).isEmpty();
        assertThat(v.receipts()).hasSize(2);
    }

    @Test
    void existingReceiptsAreReportedOnEveryRow() {
        List<ImportError> errors = validate("""
                BOS,R-1,2026-09-01T10:00:00Z,TEE-1,1,1.00
                WEB,R-1,2026-09-01T10:00:00Z,TEE-1,1,1.00
                BOS,R-1,2026-09-01T10:00:00Z,JNS-1,1,1.00
                """, Set.of(new ReceiptKey(1L, "R-1"))).errors();
        assertThat(errors).extracting(ImportError::line, ImportError::column)
                .containsExactly(tuple(2, "receipt_number"), tuple(4, "receipt_number"));
        assertThat(errors.getFirst().message()).contains("already exists for store BOS");
    }
}
