package com.oussamaksantini.insightstudio.tenancy;

/**
 * Resolves the business for the current request (docs/accounts-contract.md §3). Integrator-owned
 * interface; the backend-auth branch provides the real implementation.
 */
public interface CurrentBusiness {

    /**
     * The business this request may read. Throws an {@code ApiException}: 401 when not signed in
     * (and no public demo), 404 when the selected business is not one of the caller's memberships,
     * 400 when a choice is required.
     */
    BusinessAccess require();

    /**
     * Like {@link #require()}, and additionally requires a write-capable role of at least
     * {@code minimum}: 403 for a weaker role, for the read-only public demo, and for an account
     * whose email address is not verified.
     */
    BusinessAccess require(Role minimum);
}
