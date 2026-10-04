package com.oussamaksantini.insightstudio.billing;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Enforces the plan limits (docs/billing-contract.md §2) in every path that creates a limited thing.
 *
 * <p>Race-free: each check runs inside the creating transaction and first takes a lock per
 * (business, resource) ({@link BillingQueries#lockResource}, a transaction-scoped advisory lock), then
 * counts. Concurrent creations of the same resource in the same business therefore check one after
 * the other, each seeing the rows the previous one committed: N parallel creations with K free slots
 * give exactly K successes. Other resources and other businesses are not blocked.
 *
 * <p>Only creating more is ever refused: reading, editing, running, exporting and deleting existing
 * things are never checked, so a business above its limits after a downgrade keeps everything.
 */
@Component
public class PlanLimits {

    private final BillingQueries queries;
    private final BillingPlans plans;

    PlanLimits(BillingQueries queries, BillingPlans plans) {
        this.queries = queries;
        this.plans = plans;
    }

    /** The plan the business is on now. */
    public Plan planOf(long businessId) {
        return queries.subscription(businessId, false)
                .map(row -> plans.effective(row.provider(), row.plan(), row.status()))
                .orElse(plans.free());
    }

    /** Takes the (business, resource) lock until the transaction ends (before counting anything). */
    public void lock(long businessId, PlanResource resource) {
        requireTransaction();
        queries.lockResource(businessId, resource);
    }

    /**
     * Locks, counts and refuses one more {@code resource} when the business is at or above its limit.
     *
     * @throws PlanLimitException when the plan has no room left
     */
    public void requireRoom(long businessId, PlanResource resource) {
        lock(businessId, resource);
        exceeded(businessId, resource, 1).ifPresent(e -> {
            throw e;
        });
    }

    /**
     * Accepting an invitation: locks the members resource and refuses when the business's members
     * alone already fill the plan (the invitation's own reserved seat is not counted).
     */
    public void requireSeatForAcceptance(long businessId) {
        lock(businessId, PlanResource.MEMBERS);
        Plan plan = planOf(businessId);
        int limit = plan.limit(PlanResource.MEMBERS);
        long members = queries.members(businessId);
        if (members + 1 > limit) {
            boolean upgrade = plans.upgradeFor(plan, PlanResource.MEMBERS).isPresent();
            String detail = "This business is full: the %s plan allows %s. Ask an owner to %s."
                    .formatted(plan.name(), PlanResource.MEMBERS.count(limit),
                            upgrade ? "upgrade or remove a member first" : "remove a member first");
            throw new PlanLimitException(detail, PlanResource.MEMBERS, limit, members, plan.key(), upgrade);
        }
    }

    /**
     * The refusal for adding {@code adding} more {@code resource}, or empty when they fit. Does not lock:
     * call {@link #lock} first when the answer guards a write.
     */
    public Optional<PlanLimitException> exceeded(long businessId, PlanResource resource, long adding) {
        Plan plan = planOf(businessId);
        int limit = plan.limit(resource);
        long used = queries.used(businessId, resource);
        if (used + adding <= limit) {
            return Optional.empty();
        }
        Optional<Plan> upgrade = plans.upgradeFor(plan, resource);
        return Optional.of(new PlanLimitException(detail(plan, resource, limit, used, adding, upgrade), resource, limit,
                used, plan.key(), upgrade.isPresent()));
    }

    private static String detail(Plan plan, PlanResource resource, int limit, long used, long adding, Optional<Plan> upgrade) {
        String allows = "The %s plan allows %s".formatted(plan.name(), resource.count(limit));
        String upgradeTo = upgrade.map(p -> "Upgrade to " + p.name() + " or ").orElse("");
        return switch (resource) {
            case MEMBERS -> "%s (open invitations count). %s%s.".formatted(allows, upgradeTo,
                    upgrade.isPresent() ? "remove a member or revoke an invitation first"
                            : "Remove a member or revoke an invitation first");
            case IMPORTS_PER_MONTH -> "%s. %s.".formatted(allows,
                    upgrade.map(p -> "Upgrade to " + p.name() + " or wait until next month").orElse("Wait until next month"));
            default -> adding > 1
                    ? "This would add %s; %s and the business has %d. %s%s.".formatted(resource.count(adding),
                            allows.substring(0, 1).toLowerCase() + allows.substring(1), used, upgradeTo,
                            upgrade.isPresent() ? "delete some first" : "Delete some first")
                    : "%s. %s%s.".formatted(allows, upgradeTo, upgrade.isPresent() ? "delete one first" : "Delete one first");
        };
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Plan limits are checked inside the creating transaction.");
        }
    }
}
