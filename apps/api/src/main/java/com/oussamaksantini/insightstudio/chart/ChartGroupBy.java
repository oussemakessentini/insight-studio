package com.oussamaksantini.insightstudio.chart;

import java.util.Arrays;
import java.util.Optional;

/** How a chart groups its figures (docs/chart-builder-contract.md §1). */
public enum ChartGroupBy {
    NONE("none", "Total"),
    TIME("time", "Time"),
    STORE("store", "Store"),
    PRODUCT("product", "Product"),
    CATEGORY("category", "Category");

    private final String key;
    private final String label;

    ChartGroupBy(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** Store, product and category groups are ranked and limited to the top {@code limit}. */
    public boolean ranked() {
        return this == STORE || this == PRODUCT || this == CATEGORY;
    }

    public static Optional<ChartGroupBy> fromKey(String key) {
        return Arrays.stream(values()).filter(g -> g.key.equals(key)).findFirst();
    }
}
