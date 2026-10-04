package com.oussamaksantini.insightstudio.billing;

import com.oussamaksantini.insightstudio.account.AccountPrincipal;
import com.oussamaksantini.insightstudio.billing.FakeBillingProvider.Outcome;
import com.oussamaksantini.insightstudio.billing.FakeBillingProvider.Session;
import com.oussamaksantini.insightstudio.business.MemberAccess;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The fake provider's pages' API (docs/billing-contract.md §6, §7): only with
 * {@code insight.billing.provider=fake} (404 otherwise), for a signed-in OWNER of the session's
 * business (404 when not a member, 403 for another role).
 *
 * <ul>
 *   <li>{@code GET /api/billing/fake/{sessionId}}: the session ({@link FakeSessionResponse}).</li>
 *   <li>{@code POST /api/billing/fake/{sessionId}/{action}}: checkout {@code pay}, {@code decline},
 *       {@code cancel}; portal {@code cancel-at-period-end}, {@code resume}, {@code cancel-now},
 *       {@code fail-renewal}, {@code unpaid}, {@code pay-outstanding} (and {@code end-period}, an extra:
 *       the current period ends now). Answers the session after the action, with {@code redirectUrl}
 *       when the page should leave (pay: the success URL; cancel: the cancel URL).</li>
 * </ul>
 */
@RestController
class FakeBillingController {

    private final BillingProvider provider;
    private final MemberAccess access;
    private final BillingPlans plans;
    private final BillingQueries queries;

    FakeBillingController(BillingProvider provider, MemberAccess access, BillingPlans plans, BillingQueries queries) {
        this.provider = provider;
        this.access = access;
        this.plans = plans;
        this.queries = queries;
    }

    /**
     * A fake checkout or portal session.
     *
     * @param status checkout: {@code open}, {@code declined} (the last payment attempt was declined; pay
     *     can be tried again) or {@code complete}; portal: {@code open}
     * @param subscription the subscription the session is about, if any
     * @param actions the actions this session accepts
     * @param redirectUrl after an action: where the page should go next ({@code null}: stay)
     */
    record FakeSessionResponse(String id, String kind, long businessId, String businessName, Plan plan, String status,
            boolean declined, FakeSubscription subscription, String successUrl, String cancelUrl, String returnUrl,
            List<String> actions, String redirectUrl) {
    }

    record FakeSubscription(String status, boolean cancelAtPeriodEnd, Instant currentPeriodEnd, Instant canceledAt) {
    }

    @GetMapping("/api/billing/fake/{sessionId}")
    FakeSessionResponse get(@PathVariable String sessionId) {
        FakeBillingProvider fake = fake();
        Session session = fake.session(sessionId);
        authorize(session);
        return response(session, null);
    }

    @PostMapping("/api/billing/fake/{sessionId}/{action}")
    FakeSessionResponse act(@PathVariable String sessionId, @PathVariable String action) {
        FakeBillingProvider fake = fake();
        authorize(fake.session(sessionId));
        Outcome outcome = fake.act(sessionId, action);
        return response(fake.session(sessionId), outcome.redirectUrl());
    }

    private FakeBillingProvider fake() {
        if (provider instanceof FakeBillingProvider fake) {
            return fake;
        }
        throw ApiException.notFound("Not found.");
    }

    private void authorize(Session session) {
        AccountPrincipal caller = MemberAccess.caller();
        access.require(caller, session.businessId(), Role.OWNER);
    }

    private FakeSessionResponse response(Session session, String redirectUrl) {
        JsonNode data = session.data();
        JsonNode sub = session.subscription();
        boolean checkout = session.kind().equals("checkout");
        String planKey = checkout ? data.path("plan").asString()
                : sub == null ? null : plans.forPrice(sub.path("items").path("data").path(0).path("price").path("id").asString())
                        .map(Plan::key).orElse(null);
        Plan plan = plans.find(planKey).orElse(null);
        boolean declined = checkout && data.path("declined").asBoolean(false);
        String status = !checkout ? "open"
                : data.path("status").asString().equals("complete") ? "complete" : declined ? "declined" : "open";
        FakeSubscription subscription = sub == null ? null : new FakeSubscription(sub.path("status").asString(),
                sub.path("cancel_at_period_end").asBoolean(false),
                FakeBillingProvider.instant(sub.path("items").path("data").path(0).get("current_period_end")),
                FakeBillingProvider.instant(sub.get("canceled_at")));
        return new FakeSessionResponse(session.id(), session.kind(), session.businessId(),
                queries.businessName(session.businessId()).orElse(null), plan, status, declined, subscription,
                checkout ? data.path("success_url").asString() : null,
                checkout ? data.path("cancel_url").asString() : null,
                checkout ? null : data.path("return_url").asString(),
                checkout ? FakeBillingProvider.CHECKOUT_ACTIONS : FakeBillingProvider.PORTAL_ACTIONS,
                redirectUrl);
    }
}
