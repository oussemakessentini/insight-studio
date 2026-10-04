package com.oussamaksantini.insightstudio.billing;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A limited resource (docs/billing-contract.md §1). {@link #key()} is its name in API answers
 * ({@code usage[].resource}, a refusal's {@code resource}) and in {@code Plan.limits}.
 */
public enum PlanResource {

    /** Memberships plus open invitations (an invitation reserves a seat). */
    MEMBERS("members", "member", "members"),
    STORES("stores", "store", "stores"),
    CHARTS("charts", "chart", "charts"),
    DASHBOARDS("dashboards", "dashboard", "dashboards"),
    /** Completed (non-dry-run) imports of any kind this calendar month, in the business's time zone. */
    IMPORTS_PER_MONTH("importsPerMonth", "import per month", "imports per month");

    private final String key;
    private final String singular;
    private final String plural;

    PlanResource(String key, String singular, String plural) {
        this.key = key;
        this.singular = singular;
        this.plural = plural;
    }

    @JsonValue
    public String key() {
        return key;
    }

    /** "1 store", "3 stores", "10 imports per month". */
    public String count(long n) {
        return n + " " + (n == 1 ? singular : plural);
    }
}
