package com.oussamaksantini.insightstudio.common.web;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Rules shared by the fields of stores and products, whether they arrive as JSON or in a CSV
 * import, so both paths accept exactly the same values with the same messages. Each method returns
 * the cleaned value or throws {@link InvalidFieldException}.
 */
public final class FieldRules {

    /** Store codes and SKUs: matched exactly by CSV imports, so they are kept simple. */
    public static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$");
    /** Largest value of a NUMERIC(12,2) column. */
    public static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");

    private FieldRules() {
    }

    /** A code or SKU: 1 to 50 letters, digits, '.', '_' or '-', starting with a letter or digit. */
    public static String identifier(String value, String field) {
        String clean = value == null ? "" : value.strip();
        if (!IDENTIFIER.matcher(clean).matches()) {
            throw new InvalidFieldException(
                    "'%s' must be 1 to 50 letters, digits, '.', '_' or '-', starting with a letter or digit.".formatted(field));
        }
        return clean;
    }

    /** Trimmed text without control characters; {@code null} for an empty optional value. */
    public static String text(String value, String field, int maxLength, boolean required) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty()) {
            if (required) {
                throw new InvalidFieldException("'%s' is required.".formatted(field));
            }
            return null;
        }
        if (clean.length() > maxLength || clean.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidFieldException("'%s' must be at most %d characters of text.".formatted(field, maxLength));
        }
        return clean;
    }

    /** A price of 0 or more with at most 2 decimals, returned with scale 2. */
    public static BigDecimal price(BigDecimal value, String field) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_PRICE) > 0
                || value.stripTrailingZeros().scale() > 2) {
            throw invalidPrice(field);
        }
        return value.setScale(2);
    }

    /** The error for a value that is not a valid price, e.g. text in a CSV cell that isn't a plain number. */
    public static InvalidFieldException invalidPrice(String field) {
        return new InvalidFieldException("'%s' must be a price of 0 or more with at most 2 decimals.".formatted(field));
    }
}
