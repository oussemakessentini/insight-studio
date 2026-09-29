package com.oussamaksantini.insightstudio.sale;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Sort options for the sales list; each maps to a fixed ORDER BY, never to user input. */
enum SaleSort {
    NEWEST("s.sold_at DESC, s.id DESC"),
    OLDEST("s.sold_at ASC, s.id ASC"),
    LARGEST("total DESC, s.sold_at DESC, s.id DESC");

    private final String orderBy;

    SaleSort(String orderBy) {
        this.orderBy = orderBy;
    }

    String orderBy() {
        return orderBy;
    }

    String param() {
        return name().toLowerCase(Locale.ROOT);
    }

    static SaleSort fromParam(String value) {
        return Arrays.stream(values())
                .filter(s -> s.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid value '%s' for parameter 'sort'. Expected one of: %s.".formatted(
                                value, Arrays.stream(values()).map(SaleSort::param).collect(Collectors.joining(", ")))));
    }
}
