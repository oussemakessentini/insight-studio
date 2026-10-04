package com.oussamaksantini.insightstudio.billing;

import com.oussamaksantini.insightstudio.billing.BillingService.BillingResponse;
import com.oussamaksantini.insightstudio.billing.BillingService.UrlResponse;
import com.oussamaksantini.insightstudio.business.MemberAccess;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Billing of a business and the plans (docs/billing-contract.md §6). Every endpoint needs a session. */
@RestController
class BillingController {

    private final BillingService service;

    BillingController(BillingService service) {
        this.service = service;
    }

    record CheckoutRequest(String plan) {
    }

    @GetMapping("/api/billing/plans")
    List<Plan> plans() {
        MemberAccess.caller();
        return service.plans();
    }

    @GetMapping("/api/businesses/{businessId}/billing")
    BillingResponse billing(@PathVariable @Positive long businessId) {
        return service.billing(MemberAccess.caller(), businessId);
    }

    @PostMapping("/api/businesses/{businessId}/billing/checkout")
    UrlResponse checkout(@PathVariable @Positive long businessId, @RequestBody(required = false) CheckoutRequest body) {
        return service.checkout(MemberAccess.caller(), businessId, body == null ? null : body.plan());
    }

    @PostMapping("/api/businesses/{businessId}/billing/portal")
    UrlResponse portal(@PathVariable @Positive long businessId) {
        return service.portal(MemberAccess.caller(), businessId);
    }
}
