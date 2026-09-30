package com.oussamaksantini.insightstudio.tenancy;

/**
 * The business a request acts on, resolved on the server from the authenticated membership (or
 * the public demo), never from client input alone. Integrator-owned.
 *
 * @param businessId the resolved business; every query must be filtered by it
 * @param role the caller's role in that business; {@link Role#VIEWER} for the public demo
 * @param demo {@code true} for anonymous read-only access to the public demo business
 * @param userId the authenticated user, or {@code null} for the public demo
 * @param emailVerified whether the user verified their email address ({@code true} for the demo,
 *     which cannot write anyway); unverified accounts may read but not change anything
 */
public record BusinessAccess(long businessId, Role role, boolean demo, Long userId, boolean emailVerified) {

    /** Whether the caller may change data in this business with at least {@code minimum} role. */
    public boolean canWrite(Role minimum) {
        return !demo && emailVerified && role.atLeast(minimum);
    }
}
