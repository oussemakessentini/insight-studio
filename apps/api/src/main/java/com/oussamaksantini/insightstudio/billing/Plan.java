package com.oussamaksantini.insightstudio.billing;

/**
 * A configured plan, as the API returns it ({@code Plan} in docs/billing-contract.md §6). The
 * provider's price id is configuration only: it is never part of an answer.
 */
public record Plan(String key, String name, String priceDisplay, Limits limits) {

    /** The plan's limits ({@code Plan.limits}). */
    public record Limits(int members, int stores, int charts, int dashboards, int importsPerMonth) {

        public int of(PlanResource resource) {
            return switch (resource) {
                case MEMBERS -> members;
                case STORES -> stores;
                case CHARTS -> charts;
                case DASHBOARDS -> dashboards;
                case IMPORTS_PER_MONTH -> importsPerMonth;
            };
        }
    }

    public int limit(PlanResource resource) {
        return limits.of(resource);
    }
}
