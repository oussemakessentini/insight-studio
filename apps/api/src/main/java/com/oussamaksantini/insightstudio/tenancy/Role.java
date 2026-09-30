package com.oussamaksantini.insightstudio.tenancy;

/**
 * A member's role in a business, strongest first. See docs/accounts-contract.md §4 for what each
 * role may do. Integrator-owned: change only through the integrator.
 */
public enum Role {
    OWNER,
    ADMIN,
    VIEWER;

    /** Whether this role grants at least the permissions of {@code minimum}. */
    public boolean atLeast(Role minimum) {
        return ordinal() <= minimum.ordinal();
    }
}
