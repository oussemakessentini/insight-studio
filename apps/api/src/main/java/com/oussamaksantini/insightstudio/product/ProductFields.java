package com.oussamaksantini.insightstudio.product;

import com.oussamaksantini.insightstudio.common.web.FieldRules;
import java.math.BigDecimal;

/**
 * The rules for a product's fields, used by {@code POST /api/products} and the products CSV import
 * alike. Each method returns the cleaned value or throws
 * {@link com.oussamaksantini.insightstudio.common.web.InvalidFieldException}.
 */
public final class ProductFields {

    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_CATEGORY_LENGTH = 100;

    private ProductFields() {
    }

    public static String sku(String value) {
        return FieldRules.identifier(value, "sku");
    }

    public static String name(String value) {
        return FieldRules.text(value, "name", MAX_NAME_LENGTH, true);
    }

    public static String category(String value) {
        return FieldRules.text(value, "category", MAX_CATEGORY_LENGTH, true);
    }

    /** {@code field} is the name the caller knows the price by: {@code listPrice} in JSON, {@code list_price} in CSV. */
    public static BigDecimal listPrice(BigDecimal value, String field) {
        return FieldRules.price(value, field);
    }
}
