package com.oussamaksantini.insightstudio.chart;

import java.util.Arrays;
import java.util.Optional;

/**
 * The metrics a chart can show (docs/chart-builder-contract.md §1), defined exactly like the report
 * API: revenue at the prices charged, orders = receipts with at least one item, units = quantities,
 * average order value = revenue / orders of the same group.
 */
public enum ChartMetric {
    REVENUE("revenue", "Revenue", Unit.MONEY, true),
    ORDERS("orders", "Orders", Unit.COUNT, false),
    UNITS("units", "Units sold", Unit.COUNT, true),
    AVERAGE_ORDER_VALUE("average_order_value", "Average order value", Unit.MONEY, false);

    /** How values are formatted: in the business currency, or as whole numbers. */
    public enum Unit {
        MONEY("money"),
        COUNT("count");

        private final String key;

        Unit(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private final String key;
    private final String label;
    private final Unit unit;
    private final boolean additive;

    ChartMetric(String key, String label, Unit unit, boolean additive) {
        this.key = key;
        this.label = label;
        this.unit = unit;
        this.additive = additive;
    }

    /** The code used in definitions and results, e.g. {@code average_order_value}. */
    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public Unit unit() {
        return unit;
    }

    /**
     * Whether the groups of every grouping add up to the total. Orders only add up by time and store
     * (an order containing two products or categories counts in each); an average never does.
     */
    public boolean additive() {
        return additive;
    }

    public static Optional<ChartMetric> fromKey(String key) {
        return Arrays.stream(values()).filter(m -> m.key.equals(key)).findFirst();
    }
}
