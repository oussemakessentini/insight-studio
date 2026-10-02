package com.oussamaksantini.insightstudio.store;

import com.oussamaksantini.insightstudio.common.web.FieldRules;

/**
 * The rules for a store's fields, used by {@code POST /api/stores} and the stores CSV import alike.
 * Each method returns the cleaned value or throws
 * {@link com.oussamaksantini.insightstudio.common.web.InvalidFieldException}.
 */
public final class StoreFields {

    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_CITY_LENGTH = 100;

    private StoreFields() {
    }

    public static String code(String value) {
        return FieldRules.identifier(value, "code");
    }

    public static String name(String value) {
        return FieldRules.text(value, "name", MAX_NAME_LENGTH, true);
    }

    /** Optional: {@code null} when empty. */
    public static String city(String value) {
        return FieldRules.text(value, "city", MAX_CITY_LENGTH, false);
    }
}
