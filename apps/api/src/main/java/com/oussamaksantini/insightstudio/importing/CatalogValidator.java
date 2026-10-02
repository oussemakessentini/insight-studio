package com.oussamaksantini.insightstudio.importing;

import com.oussamaksantini.insightstudio.common.web.FieldRules;
import com.oussamaksantini.insightstudio.common.web.InvalidFieldException;
import com.oussamaksantini.insightstudio.importing.CsvParser.CsvRecord;
import com.oussamaksantini.insightstudio.importing.dto.ImportError;
import com.oussamaksantini.insightstudio.product.ProductFields;
import com.oussamaksantini.insightstudio.store.StoreFields;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Checks the rows of a stores or products import (already in the kind's field order, see
 * {@link ColumnMapping}) with the same field rules as {@code POST /api/stores} and
 * {@code POST /api/products}, and plans what to create and update in the current business
 * according to the {@link ImportMode}. Codes and SKUs are matched exactly (case-sensitive). Pure
 * logic: the business's existing stores or products are passed in.
 */
final class CatalogValidator {

    static final String CODE = "code";
    static final String SKU = "sku";
    static final String NAME = "name";
    static final String CITY = "city";
    static final String CATEGORY = "category";
    static final String LIST_PRICE = "list_price";

    /** Digits with an optional fraction of one or two digits, as for sales prices. */
    private static final Pattern PRICE = Pattern.compile("\\d{1,10}(\\.\\d{1,2})?");

    record ExistingStore(long id, String code, String name, String city) {
    }

    record ExistingProduct(long id, String sku, String name, String category, BigDecimal listPrice) {
    }

    /** A store's values after the import; {@code id} is {@code null} for a store to create. */
    record StoreRow(Long id, int line, String code, String name, String city) {
    }

    /** A product's values after the import; {@code id} is {@code null} for a product to create. */
    record ProductRow(Long id, int line, String sku, String name, String category, BigDecimal listPrice) {
    }

    /**
     * What a valid file would change. Only safe to write when {@code errors} is empty; otherwise the
     * counts cover the rows without errors.
     *
     * @param creates rows whose key is new, in file order
     * @param updates rows whose key exists and whose values differ ({@code create_or_update} only)
     * @param unchanged rows whose key exists with identical values ({@code create_or_update} only)
     * @param categoryChanges products among {@code updates} whose category changes
     */
    record Plan<T>(List<T> creates, List<T> updates, int unchanged, int categoryChanges, List<ImportError> errors) {
    }

    private CatalogValidator() {
    }

    /**
     * @param cityMapped whether a column feeds {@code city}: when it doesn't, an update keeps the stored city
     * @param existing the business's stores by code
     */
    static Plan<StoreRow> stores(List<CsvRecord> rows, boolean cityMapped, ImportMode mode,
            Map<String, ExistingStore> existing) {
        List<ImportError> errors = new ArrayList<>();
        Map<String, Integer> lines = new HashMap<>();
        List<StoreRow> creates = new ArrayList<>();
        List<StoreRow> updates = new ArrayList<>();
        int unchanged = 0;

        for (CsvRecord record : rows) {
            int line = record.line();
            List<String> values = record.fields();
            int errorsBefore = errors.size();
            String code = check(line, CODE, () -> StoreFields.code(values.get(0)), errors);
            String name = check(line, NAME, () -> StoreFields.name(values.get(1)), errors);
            String city = cityMapped ? check(line, CITY, () -> StoreFields.city(values.get(2)), errors) : null;
            ExistingStore current = null;
            if (code != null) {
                current = existing.get(code);
                if (!checkKey(line, CODE, code, "store code", "Store code", current != null, mode, lines, errors)) {
                    continue;
                }
            }
            if (errors.size() > errorsBefore) {
                continue;
            }
            if (current == null) {
                creates.add(new StoreRow(null, line, code, name, city));
                continue;
            }
            String newCity = cityMapped ? city : current.city();
            if (current.name().equals(name) && Objects.equals(current.city(), newCity)) {
                unchanged++;
            } else {
                updates.add(new StoreRow(current.id(), line, code, name, newCity));
            }
        }
        return new Plan<>(creates, updates, unchanged, 0, errors);
    }

    /** @param existing the business's products by SKU */
    static Plan<ProductRow> products(List<CsvRecord> rows, ImportMode mode, Map<String, ExistingProduct> existing) {
        List<ImportError> errors = new ArrayList<>();
        Map<String, Integer> lines = new HashMap<>();
        List<ProductRow> creates = new ArrayList<>();
        List<ProductRow> updates = new ArrayList<>();
        int unchanged = 0;
        int categoryChanges = 0;

        for (CsvRecord record : rows) {
            int line = record.line();
            List<String> values = record.fields();
            int errorsBefore = errors.size();
            String sku = check(line, SKU, () -> ProductFields.sku(values.get(0)), errors);
            String name = check(line, NAME, () -> ProductFields.name(values.get(1)), errors);
            String category = check(line, CATEGORY, () -> ProductFields.category(values.get(2)), errors);
            BigDecimal listPrice = check(line, LIST_PRICE, () -> listPrice(values.get(3)), errors);
            ExistingProduct current = null;
            if (sku != null) {
                current = existing.get(sku);
                if (!checkKey(line, SKU, sku, "SKU", "SKU", current != null, mode, lines, errors)) {
                    continue;
                }
            }
            if (errors.size() > errorsBefore) {
                continue;
            }
            if (current == null) {
                creates.add(new ProductRow(null, line, sku, name, category, listPrice));
                continue;
            }
            boolean categoryChanged = !current.category().equals(category);
            if (current.name().equals(name) && !categoryChanged && current.listPrice().compareTo(listPrice) == 0) {
                unchanged++;
            } else {
                updates.add(new ProductRow(current.id(), line, sku, name, category, listPrice));
                if (categoryChanged) {
                    categoryChanges++;
                }
            }
        }
        return new Plan<>(creates, updates, unchanged, categoryChanges, errors);
    }

    /**
     * Rejects a key seen earlier in the file, and an existing key in {@code create_only} mode.
     * Returns whether the row may still be planned.
     */
    private static boolean checkKey(int line, String field, String key, String noun, String capitalised, boolean exists,
            ImportMode mode, Map<String, Integer> lines, List<ImportError> errors) {
        Integer first = lines.putIfAbsent(key, line);
        if (first != null) {
            errors.add(ImportError.cell(line, field, "Duplicate %s '%s' in this file (line %d).".formatted(noun, key, first)));
            return false;
        }
        if (exists && mode == ImportMode.CREATE_ONLY) {
            errors.add(ImportError.cell(line, field,
                    "%s '%s' already exists. Choose “Create and update” to change it.".formatted(capitalised, key)));
            return false;
        }
        return true;
    }

    private static BigDecimal listPrice(String raw) {
        String text = raw.strip();
        if (text.isEmpty()) {
            throw new InvalidFieldException("'%s' is required.".formatted(LIST_PRICE));
        }
        if (!PRICE.matcher(text).matches()) {
            // No sign, currency symbol, thousands separator or exponent; the same message as the API's rule.
            throw FieldRules.invalidPrice(LIST_PRICE);
        }
        return ProductFields.listPrice(new BigDecimal(text), LIST_PRICE);
    }

    /** Applies a field rule, turning its {@link InvalidFieldException} into a row error; {@code null} when invalid or empty. */
    private static <T> T check(int line, String field, Supplier<T> rule, List<ImportError> errors) {
        try {
            return rule.get();
        } catch (InvalidFieldException e) {
            errors.add(ImportError.cell(line, field, e.getMessage()));
            return null;
        }
    }
}
