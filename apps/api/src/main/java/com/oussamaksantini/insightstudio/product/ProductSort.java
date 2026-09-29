package com.oussamaksantini.insightstudio.product;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Sort options for the product list. The SQL expression is fixed per option, so user input never
 * reaches the ORDER BY clause.
 */
enum ProductSort {
    REVENUE("revenue", Direction.DESC),
    UNITS("units", Direction.DESC),
    NAME("LOWER(p.name)", Direction.ASC),
    SKU("p.sku", Direction.ASC),
    PRICE("p.list_price", Direction.DESC);

    enum Direction {
        ASC,
        DESC;

        String param() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Direction fromParam(String value) {
            return parse(values(), value, "direction");
        }
    }

    private final String sqlExpression;
    private final Direction defaultDirection;

    ProductSort(String sqlExpression, Direction defaultDirection) {
        this.sqlExpression = sqlExpression;
        this.defaultDirection = defaultDirection;
    }

    String sqlExpression() {
        return sqlExpression;
    }

    Direction defaultDirection() {
        return defaultDirection;
    }

    String param() {
        return name().toLowerCase(Locale.ROOT);
    }

    static ProductSort fromParam(String value) {
        return parse(values(), value, "sort");
    }

    private static <E extends Enum<E>> E parse(E[] options, String value, String parameter) {
        return Arrays.stream(options)
                .filter(o -> o.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid value '%s' for parameter '%s'. Expected one of: %s.".formatted(
                                value, parameter,
                                Arrays.stream(options).map(o -> o.name().toLowerCase(Locale.ROOT))
                                        .collect(Collectors.joining(", ")))));
    }
}
