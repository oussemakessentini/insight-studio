package com.oussamaksantini.insightstudio.importing;

import com.oussamaksantini.insightstudio.importing.CsvParser.CsvRecord;
import com.oussamaksantini.insightstudio.importing.dto.ImportError;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Checks the data rows of a sales import, already in {@link #COLUMNS} order (see {@link ColumnMapping}),
 * against the CSV rules and the current business's stores and products, and groups valid rows into
 * receipts. Errors name the field; the service adds the file's own column. Pure logic: the database
 * lookups it needs are passed in, so it can be tested without Spring.
 */
final class ImportValidator {

    static final String STORE_CODE = "store_code";
    static final String RECEIPT_NUMBER = "receipt_number";
    static final String SOLD_AT = "sold_at";
    static final String SKU = "sku";
    static final String QUANTITY = "quantity";
    static final String UNIT_PRICE = "unit_price";

    /** The required header, in order. */
    static final List<String> COLUMNS = List.of(STORE_CODE, RECEIPT_NUMBER, SOLD_AT, SKU, QUANTITY, UNIT_PRICE);

    static final int MAX_RECEIPT_NUMBER_LENGTH = 40;

    /** Digits with an optional fraction of one or two digits; below NUMERIC(12,2)'s limit. */
    private static final Pattern PRICE = Pattern.compile("\\d{1,10}(\\.\\d{1,2})?");
    private static final Pattern QUANTITY_DIGITS = Pattern.compile("\\d{1,9}");

    /** Stores and products of the business, and its time zone for local times. */
    record Catalog(Map<String, Long> storeIdsByCode, Map<String, Long> productIdsBySku, ZoneId zone) {
    }

    /** A receipt as identified in the database: store and receipt number. */
    record ReceiptKey(long storeId, String receiptNumber) {
    }

    record PlannedLine(int line, long productId, String sku, int quantity, BigDecimal unitPrice) {

        BigDecimal amount() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }

    record PlannedReceipt(ReceiptKey key, String storeCode, OffsetDateTime soldAt, List<PlannedLine> lines) {
    }

    /**
     * @param receipts receipts built from well-formed rows, in file order; only safe to write when {@code errors} is empty
     * @param errors every error found, ordered by line
     */
    record Validation(int rowCount, List<PlannedReceipt> receipts, List<ImportError> errors) {

        int lineCount() {
            return receipts.stream().mapToInt(r -> r.lines().size()).sum();
        }

        BigDecimal totalAmount() {
            return receipts.stream()
                    .flatMap(r -> r.lines().stream())
                    .map(PlannedLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2);
        }
    }

    private record Row(int line, String storeCode, Long storeId, String receiptNumber, OffsetDateTime soldAt,
            String sku, Long productId, Integer quantity, BigDecimal unitPrice) {

        boolean complete() {
            return storeId != null && receiptNumber != null && soldAt != null
                    && productId != null && quantity != null && unitPrice != null;
        }
    }

    private ImportValidator() {
    }

    /** Whether {@code header} matches {@link #COLUMNS}, ignoring case and surrounding spaces. */
    static boolean isValidHeader(List<String> header) {
        if (header.size() != COLUMNS.size()) {
            return false;
        }
        for (int i = 0; i < COLUMNS.size(); i++) {
            if (!COLUMNS.get(i).equalsIgnoreCase(header.get(i).strip())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates data rows (the header already removed).
     *
     * @param existingReceipts returns which of the given receipts already exist in the database
     */
    static Validation validate(
            List<CsvRecord> rows, Catalog catalog, Function<Collection<ReceiptKey>, Set<ReceiptKey>> existingReceipts) {
        List<ImportError> errors = new ArrayList<>();
        Map<String, List<Row>> byReceipt = new LinkedHashMap<>();

        for (CsvRecord record : rows) {
            Row row = parseRow(record, catalog, errors);
            if (row != null && row.storeCode() != null && row.receiptNumber() != null) {
                byReceipt.computeIfAbsent(row.storeCode() + "\u0000" + row.receiptNumber(), k -> new ArrayList<>()).add(row);
            }
        }

        List<PlannedReceipt> receipts = new ArrayList<>();
        for (List<Row> group : byReceipt.values()) {
            PlannedReceipt receipt = checkReceipt(group, errors);
            if (receipt != null) {
                receipts.add(receipt);
            }
        }

        if (!receipts.isEmpty()) {
            Set<ReceiptKey> existing = existingReceipts.apply(receipts.stream().map(PlannedReceipt::key).toList());
            for (PlannedReceipt receipt : receipts) {
                if (existing.contains(receipt.key())) {
                    for (PlannedLine line : receipt.lines()) {
                        errors.add(ImportError.cell(line.line(), RECEIPT_NUMBER,
                                "Receipt %s already exists for store %s; existing receipts are never overwritten."
                                        .formatted(receipt.key().receiptNumber(), receipt.storeCode())));
                    }
                }
            }
        }

        errors.sort(Comparator.comparing(ImportError::line, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(e -> e.field() == null ? -1 : COLUMNS.indexOf(e.field())));
        return new Validation(rows.size(), receipts, errors);
    }

    /** Parses one row, adding an error per invalid cell. Returns {@code null} when the row can't be read at all. */
    private static Row parseRow(CsvRecord record, Catalog catalog, List<ImportError> errors) {
        int line = record.line();
        List<String> fields = record.fields();
        if (fields.size() != COLUMNS.size()) {
            errors.add(ImportError.row(line, "Expected %d values but found %d.".formatted(COLUMNS.size(), fields.size())));
            return null;
        }

        String storeCode = required(fields.get(0), line, STORE_CODE, errors);
        Long storeId = null;
        if (storeCode != null) {
            storeId = catalog.storeIdsByCode().get(storeCode);
            if (storeId == null) {
                errors.add(ImportError.cell(line, STORE_CODE, "Unknown store code '%s'.".formatted(storeCode)));
            }
        }

        String receiptNumber = required(fields.get(1), line, RECEIPT_NUMBER, errors);
        if (receiptNumber != null && receiptNumber.length() > MAX_RECEIPT_NUMBER_LENGTH) {
            errors.add(ImportError.cell(line, RECEIPT_NUMBER,
                    "Must be at most %d characters (found %d).".formatted(MAX_RECEIPT_NUMBER_LENGTH, receiptNumber.length())));
            receiptNumber = null;
        }

        OffsetDateTime soldAt = null;
        String soldAtText = required(fields.get(2), line, SOLD_AT, errors);
        if (soldAtText != null) {
            soldAt = parseSoldAt(soldAtText, catalog.zone(), line, errors);
        }

        String sku = required(fields.get(3), line, SKU, errors);
        Long productId = null;
        if (sku != null) {
            productId = catalog.productIdsBySku().get(sku);
            if (productId == null) {
                errors.add(ImportError.cell(line, SKU, "Unknown SKU '%s'.".formatted(sku)));
            }
        }

        Integer quantity = null;
        String quantityText = required(fields.get(4), line, QUANTITY, errors);
        if (quantityText != null) {
            if (QUANTITY_DIGITS.matcher(quantityText).matches() && Integer.parseInt(quantityText) > 0) {
                quantity = Integer.parseInt(quantityText);
            } else {
                errors.add(ImportError.cell(line, QUANTITY,
                        "Must be a whole number greater than 0 (found '%s').".formatted(quantityText)));
            }
        }

        BigDecimal unitPrice = null;
        String priceText = required(fields.get(5), line, UNIT_PRICE, errors);
        if (priceText != null) {
            if (PRICE.matcher(priceText).matches()) {
                unitPrice = new BigDecimal(priceText).setScale(2);
            } else {
                errors.add(ImportError.cell(line, UNIT_PRICE,
                        "Must be a number of at least 0 with at most 2 decimals, e.g. 24.50 (found '%s')."
                                .formatted(priceText)));
            }
        }

        return new Row(line, storeCode, storeId, receiptNumber, soldAt, sku, productId, quantity, unitPrice);
    }

    /**
     * {@code sold_at} with an offset ({@code 2026-09-01T14:30:00-04:00}, {@code ...Z}) is taken as
     * is; without one it is a local time in the business's time zone. A local time skipped by a
     * daylight-saving change is an error; an ambiguous one (clocks going back) uses the earlier offset.
     */
    private static OffsetDateTime parseSoldAt(String text, ZoneId zone, int line, List<ImportError> errors) {
        try {
            return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // Not an offset date-time; try a local one below.
        }
        LocalDateTime local;
        try {
            local = LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            errors.add(ImportError.cell(line, SOLD_AT,
                    "Must be an ISO-8601 date-time such as 2026-09-01T14:30:00-04:00, 2026-09-01T18:30:00Z "
                            + "or 2026-09-01T14:30:00 (business time zone); found '%s'.".formatted(text)));
            return null;
        }
        if (zone.getRules().getValidOffsets(local).isEmpty()) {
            errors.add(ImportError.cell(line, SOLD_AT,
                    "%s does not exist in %s (daylight-saving change); add an explicit offset.".formatted(text, zone)));
            return null;
        }
        try {
            return ZonedDateTime.of(local, zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
        } catch (DateTimeException e) {
            errors.add(ImportError.cell(line, SOLD_AT, "Invalid date-time '%s'.".formatted(text)));
            return null;
        }
    }

    /**
     * Checks that the rows of one receipt agree with each other. Returns the receipt built from its
     * well-formed rows (for counts and the duplicate check), or {@code null} when none are usable.
     */
    private static PlannedReceipt checkReceipt(List<Row> rows, List<ImportError> errors) {
        Row first = rows.getFirst();
        OffsetDateTime soldAt = null;
        int soldAtLine = 0;
        Map<String, Integer> skuLines = new LinkedHashMap<>();
        List<PlannedLine> lines = new ArrayList<>();

        for (Row row : rows) {
            if (row.soldAt() != null) {
                if (soldAt == null) {
                    soldAt = row.soldAt();
                    soldAtLine = row.line();
                } else if (!soldAt.isEqual(row.soldAt())) {
                    errors.add(ImportError.cell(row.line(), SOLD_AT,
                            "All rows of receipt %s must have the same sold_at as line %d."
                                    .formatted(row.receiptNumber(), soldAtLine)));
                }
            }
            if (row.sku() != null) {
                Integer previous = skuLines.putIfAbsent(row.sku(), row.line());
                if (previous != null) {
                    errors.add(ImportError.cell(row.line(), SKU,
                            "SKU %s already appears in receipt %s on line %d; combine the quantities into one row."
                                    .formatted(row.sku(), row.receiptNumber(), previous)));
                }
            }
            if (row.complete()) {
                lines.add(new PlannedLine(row.line(), row.productId(), row.sku(), row.quantity(), row.unitPrice()));
            }
        }
        if (first.storeId() == null || soldAt == null || lines.isEmpty()) {
            return null;
        }
        return new PlannedReceipt(
                new ReceiptKey(first.storeId(), first.receiptNumber()), first.storeCode(), soldAt, List.copyOf(lines));
    }

    private static String required(String raw, int line, String column, List<ImportError> errors) {
        String value = raw.strip();
        if (value.isEmpty()) {
            errors.add(ImportError.cell(line, column, "A value is required."));
            return null;
        }
        return value;
    }
}
