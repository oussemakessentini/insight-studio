package com.oussamaksantini.insightstudio.billing;

import com.oussamaksantini.insightstudio.account.AccountPrincipal;
import com.oussamaksantini.insightstudio.account.AccountProperties;
import com.oussamaksantini.insightstudio.billing.BillingOperations.Operation;
import com.oussamaksantini.insightstudio.billing.BillingProvider.BillingUnavailableException;
import com.oussamaksantini.insightstudio.billing.BillingProvider.CheckoutSession;
import com.oussamaksantini.insightstudio.billing.BillingQueries.SubscriptionRow;
import com.oussamaksantini.insightstudio.business.BusinessService;
import com.oussamaksantini.insightstudio.business.MemberAccess;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.common.web.ServiceUnavailableException;
import com.oussamaksantini.insightstudio.tenancy.PublicDemo;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The billing endpoints (docs/billing-contract.md §6). The business in the path is a selector as
 * everywhere: 404 when the caller is not a member (the public demo has none), 403 for a weaker role.
 * With billing off ({@code provider=none}) and for the configured public demo business, every billing
 * endpoint is a 404. Checkout and portal need a verified OWNER; their return addresses come only from
 * configuration ({@code WEB_BASE_URL}), never from the request.
 *
 * <p>No database transaction is open while the provider is called: checks and the operation records
 * ({@link BillingOperations}) are written in short transactions before and after each call.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    static final String NOT_FOUND = "Not found.";
    static final String UNAVAILABLE = "Billing is temporarily unavailable. Try again in a moment.";
    static final String SUCCESS_PATH = "/settings/billing?checkout=success";
    static final String CANCELED_PATH = "/settings/billing?checkout=canceled";
    static final String RETURN_PATH = "/settings/billing";

    private final BillingPlans plans;
    private final BillingQueries queries;
    private final BillingProvider provider;
    private final MemberAccess access;
    private final PublicDemo demo;
    private final AccountProperties web;
    private final BillingOperations operations;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;

    BillingService(BillingPlans plans, BillingQueries queries, BillingProvider provider, MemberAccess access,
            PublicDemo demo, AccountProperties web, BillingOperations operations, PlatformTransactionManager manager) {
        this.plans = plans;
        this.queries = queries;
        this.provider = provider;
        this.access = access;
        this.demo = demo;
        this.web = web;
        this.operations = operations;
        this.transactions = new TransactionTemplate(manager);
        this.readOnly = new TransactionTemplate(manager);
        this.readOnly.setReadOnly(true);
    }

    /** {@code Billing}. */
    public record BillingResponse(Plan plan, SubscriptionInfo subscription, boolean paymentProblem, String provider,
            boolean canManage, List<Usage> usage, List<Plan> plans) {
    }

    /** The business's subscription as last confirmed by the provider. */
    public record SubscriptionInfo(String status, boolean cancelAtPeriodEnd, Instant currentPeriodEnd, Instant canceledAt) {
    }

    public record Usage(PlanResource resource, long used, int limit) {
    }

    public record UrlResponse(String url) {
    }

    /** Signed in: the plans, free first. */
    public List<Plan> plans() {
        requireEnabled();
        return plans.all();
    }

    /** ADMIN+: the business's plan, subscription and usage. */
    public BillingResponse billing(AccountPrincipal caller, long businessId) {
        requireEnabled();
        Role role = access.require(caller, businessId, Role.ADMIN);
        requireNotDemo(businessId);
        return readOnly.execute(status -> {
            SubscriptionRow row = queries.subscription(businessId, false)
                    .filter(r -> r.provider().equals(provider.name()))
                    .orElse(null);
            Plan plan = row == null ? plans.free() : plans.effective(row.provider(), row.plan(), row.status());
            SubscriptionInfo subscription = row == null || "none".equals(row.status()) ? null
                    : new SubscriptionInfo(row.status(), row.cancelAtPeriodEnd(), row.currentPeriodEnd(), row.canceledAt());
            boolean paymentProblem = row != null && ("past_due".equals(row.status()) || "unpaid".equals(row.status()));
            Map<PlanResource, Long> used = queries.usage(businessId);
            List<Usage> usage = Arrays.stream(PlanResource.values())
                    .map(r -> new Usage(r, used.get(r), plan.limit(r)))
                    .toList();
            return new BillingResponse(plan, subscription, paymentProblem, provider.name(), role == Role.OWNER, usage,
                    plans.all());
        });
    }

    /**
     * Verified OWNER: starts a checkout of {@code planKey}. 409 when the business is already on that plan
     * (the portal manages it), 503 when the provider can't be reached.
     */
    public UrlResponse checkout(AccountPrincipal caller, long businessId, String planKey) {
        requireEnabled();
        access.requireVerified(caller, businessId, Role.OWNER);
        requireNotDemo(businessId);
        Plan plan = plans.purchasable(planKey == null ? null : planKey.strip())
                .orElseThrow(() -> ApiException.badRequest("'plan' must be a paid plan, e.g. \"pro\"."));
        String name = provider.name();
        // 1. Short transaction: the checks, and the customer to create if the business has none yet.
        Prepared prepared = transactions.execute(status -> {
            SubscriptionRow row = queries.lockOrCreate(businessId, name);
            Plan current = plans.effective(row.provider(), row.plan(), row.status());
            if (!current.key().equals(BillingPlans.FREE)) {
                throw ApiException.conflict("This business is already on the %s plan. Use Manage billing to change it."
                        .formatted(current.name()));
            }
            String customer = row.provider().equals(name) ? row.customerId() : null;
            if (customer != null) {
                return new Prepared(customer, null, null);
            }
            String businessName = queries.businessName(businessId).orElseThrow(() -> ApiException.notFound(BusinessService.NOT_FOUND));
            return new Prepared(null, operations.pendingOrNew(businessId, name, BillingOperations.CREATE_CUSTOMER, null, null),
                    businessName);
        });
        CheckoutSession session;
        try {
            String customer = prepared.customer() != null ? prepared.customer() : createCustomer(businessId, prepared);
            String linked = customer;
            // 2. The checkout, recorded before the call; a timed-out attempt is retried with the same key.
            Operation checkoutOp = transactions.execute(status -> operations.pendingOrNew(businessId, name,
                    BillingOperations.CREATE_CHECKOUT, plan.key(), linked));
            CheckoutSession created = call(checkoutOp, () -> provider.createCheckout(businessId, linked, plan,
                    web.page(SUCCESS_PATH), web.page(CANCELED_PATH), checkoutOp.idempotencyKey()));
            session = created;
            // 3. Short transaction: completed, unless the business was deleted meanwhile; then the session
            //    just created must never be paid, and is queued for expiry.
            boolean live = Boolean.TRUE.equals(transactions.execute(status -> {
                boolean exists = queries.lockBusiness(businessId);
                Operation op = operations.lock(checkoutOp.id()).orElseThrow();
                if (!exists || "ABANDONED".equals(op.status())) {
                    operations.abandoned(op.id(), "Business deleted while its checkout was created", created.id());
                    operations.queueExpiry(businessId, name, created.id());
                    return false;
                }
                operations.succeeded(op.id(), null, created.id());
                return true;
            }));
            if (!live) {
                log.info("Business {} was deleted while its checkout was created; checkout queued for expiry.", businessId);
                throw ApiException.notFound(BusinessService.NOT_FOUND);
            }
        } catch (BillingUnavailableException e) {
            log.warn("Checkout for business {} failed: {}", businessId, e.getMessage());
            throw new ServiceUnavailableException(UNAVAILABLE, 30);
        }
        log.info("Account {} started a {} checkout for business {}.", caller.userId(), plan.key(), businessId);
        return new UrlResponse(session.url());
    }

    /** What the checkout's first transaction found: the linked customer, or the operation creating one. */
    private record Prepared(String customer, Operation customerOp, String businessName) {
    }

    /** Creates the business's provider customer (outside any transaction) and links it in a short one. */
    private String createCustomer(long businessId, Prepared prepared) {
        Operation op = prepared.customerOp();
        String created = call(op, () -> provider.createCustomer(businessId, prepared.businessName(), op.idempotencyKey()));
        String linked = transactions.execute(status -> {
            if (!queries.lockBusiness(businessId)) {
                operations.abandoned(op.id(), "Business deleted while its customer was created", null);
                return null;
            }
            operations.succeeded(op.id(), created, null);
            SubscriptionRow row = queries.lockOrCreate(businessId, provider.name());
            if (row.provider().equals(provider.name()) && row.customerId() != null) {
                return row.customerId();   // a concurrent request linked one first (same key: the same customer)
            }
            queries.setCustomer(businessId, provider.name(), created);
            return created;
        });
        if (linked == null) {
            throw ApiException.notFound(BusinessService.NOT_FOUND);
        }
        return linked;
    }

    /** Runs one provider call of an operation; a failure is recorded on the operation (own short statement). */
    private <T> T call(Operation op, Supplier<T> request) {
        try {
            return request.get();
        } catch (BillingUnavailableException e) {
            operations.failed(op.id(), BillingEventWorker.describe(e), e.retrySameKey());
            throw e;
        }
    }

    /** Verified OWNER: opens the provider's billing portal. 409 when the business has no billing account yet. */
    public UrlResponse portal(AccountPrincipal caller, long businessId) {
        requireEnabled();
        access.requireVerified(caller, businessId, Role.OWNER);
        requireNotDemo(businessId);
        String customer = queries.subscription(businessId, false)
                .filter(r -> r.provider().equals(provider.name()))
                .map(SubscriptionRow::customerId)
                .orElseThrow(() -> ApiException.conflict("This business has no billing account yet. Upgrade to a paid plan first."));
        try {
            return new UrlResponse(provider.createPortal(businessId, customer, web.page(RETURN_PATH)));
        } catch (BillingUnavailableException e) {
            log.warn("Billing portal for business {} failed: {}", businessId, e.getMessage());
            throw new ServiceUnavailableException(UNAVAILABLE, 30);
        }
    }

    private void requireEnabled() {
        if (!plans.enabled()) {
            throw ApiException.notFound(NOT_FOUND);
        }
    }

    /** The configured public demo business (by slug, even if an operator gave it members) has no billing. */
    private void requireNotDemo(long businessId) {
        if (queries.businessSlug(businessId).filter(slug -> slug.equalsIgnoreCase(demo.reservedSlug())).isPresent()) {
            throw ApiException.notFound(BusinessService.NOT_FOUND);
        }
    }
}
