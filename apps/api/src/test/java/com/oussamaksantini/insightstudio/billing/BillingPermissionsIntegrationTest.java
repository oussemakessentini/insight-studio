package com.oussamaksantini.insightstudio.billing;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.as;
import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import com.oussamaksantini.insightstudio.tenancy.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Who may use which billing endpoint (docs/billing-contract.md §6): OWNER, ADMIN, VIEWER, an unverified
 * owner, signed-out callers, members of another business and the public demo business.
 */
class BillingPermissionsIntegrationTest extends BillingIntegrationTest {

    Shop shop;
    TestUser admin;
    TestUser viewer;
    TestUser unverifiedOwner;
    Shop other;

    @BeforeEach
    void setUp() {
        shop = shop("Corner Shop");
        admin = accounts.member("admin@corner.test", shop.id(), Role.ADMIN);
        viewer = accounts.member("viewer@corner.test", shop.id(), Role.VIEWER);
        unverifiedOwner = accounts.unverifiedUser("late@corner.test");
        accounts.member(unverifiedOwner, shop.id(), Role.OWNER);
        other = shop("Other Shop");
    }

    private MockHttpServletRequestBuilder billingGet(long id) {
        return get("/api/businesses/%d/billing".formatted(id));
    }

    private MockHttpServletRequestBuilder checkoutPost(long id) {
        return json(post("/api/businesses/%d/billing/checkout".formatted(id)), "{\"plan\":\"pro\"}");
    }

    private MockHttpServletRequestBuilder portalPost(long id) {
        return post("/api/businesses/%d/billing/portal".formatted(id));
    }

    @Test
    void ownerSeesBillingAndManagesIt() throws Exception {
        mvc.perform(billingGet(shop.id()).with(as(shop.owner(), shop.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.key").value("free"))
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.provider").value("fake"))
                .andExpect(jsonPath("$.subscription").isEmpty())
                .andExpect(jsonPath("$.paymentProblem").value(false))
                .andExpect(jsonPath("$.usage.length()").value(5))
                .andExpect(jsonPath("$.usage[0].resource").value("members"))
                .andExpect(jsonPath("$.usage[0].used").value(4))
                .andExpect(jsonPath("$.usage[0].limit").value(3))
                .andExpect(jsonPath("$.usage[4].resource").value("importsPerMonth"))
                .andExpect(jsonPath("$.plans[0].key").value("free"))
                .andExpect(jsonPath("$.plans[1].key").value("pro"))
                .andExpect(jsonPath("$.plans[1].priceDisplay").value("$29 / month"))
                .andExpect(jsonPath("$.plans[1].limits.importsPerMonth").value(500))
                .andExpect(jsonPath("$.plans[1].providerPriceId").doesNotExist());
        // No billing account yet: the portal has nothing to manage.
        mvc.perform(portalPost(shop.id()).with(as(shop.owner(), shop.id()))).andExpect(status().isConflict());
        checkout(shop);
        portal(shop);
    }

    @Test
    void adminSeesBillingButCannotChangeThePlan() throws Exception {
        mvc.perform(billingGet(shop.id()).with(as(admin, shop.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(false));
        mvc.perform(checkoutPost(shop.id()).with(as(admin, shop.id()))).andExpect(status().isForbidden());
        mvc.perform(portalPost(shop.id()).with(as(admin, shop.id()))).andExpect(status().isForbidden());
    }

    @Test
    void viewerHasNoAccess() throws Exception {
        mvc.perform(billingGet(shop.id()).with(as(viewer, shop.id()))).andExpect(status().isForbidden());
        mvc.perform(checkoutPost(shop.id()).with(as(viewer, shop.id()))).andExpect(status().isForbidden());
        mvc.perform(portalPost(shop.id()).with(as(viewer, shop.id()))).andExpect(status().isForbidden());
    }

    @Test
    void anUnverifiedOwnerMaySeeButNotPay() throws Exception {
        mvc.perform(billingGet(shop.id()).with(as(unverifiedOwner, shop.id()))).andExpect(status().isOk());
        mvc.perform(checkoutPost(shop.id()).with(as(unverifiedOwner, shop.id()))).andExpect(status().isForbidden());
        mvc.perform(portalPost(shop.id()).with(as(unverifiedOwner, shop.id()))).andExpect(status().isForbidden());
    }

    @Test
    void signedOutCallersAreUnauthorized() throws Exception {
        mvc.perform(billingGet(shop.id())).andExpect(status().isUnauthorized());
        mvc.perform(checkoutPost(shop.id()).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(portalPost(shop.id()).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/billing/plans")).andExpect(status().isUnauthorized());
        String session = checkout(shop);
        mvc.perform(get("/api/billing/fake/" + session)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/billing/fake/%s/pay".formatted(session)).with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void membersOfAnotherBusinessGetNotFound() throws Exception {
        mvc.perform(billingGet(shop.id()).with(as(other.owner(), other.id()))).andExpect(status().isNotFound());
        mvc.perform(checkoutPost(shop.id()).with(as(other.owner(), other.id()))).andExpect(status().isNotFound());
        mvc.perform(portalPost(shop.id()).with(as(other.owner(), other.id()))).andExpect(status().isNotFound());
    }

    @Test
    void writesNeedTheCsrfHeader() throws Exception {
        mvc.perform(checkoutPost(shop.id()).with(TestAccountsNoCsrf.as(shop.owner(), shop.id())))
                .andExpect(status().isForbidden());
    }

    @Test
    void everySignedInAccountSeesThePlans() throws Exception {
        mvc.perform(get("/api/billing/plans").with(as(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("free"))
                .andExpect(jsonPath("$[0].limits.stores").value(2))
                .andExpect(jsonPath("$[1].limits.stores").value(50));
    }

    @Test
    void fakeSessionsBelongToTheirBusinessOwners() throws Exception {
        String session = checkout(shop);
        mvc.perform(get("/api/billing/fake/" + session).with(as(shop.owner(), shop.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("checkout"))
                .andExpect(jsonPath("$.businessId").value(shop.id()))
                .andExpect(jsonPath("$.businessName").value("Corner Shop"))
                .andExpect(jsonPath("$.plan.key").value("pro"))
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.subscription").isEmpty());
        mvc.perform(get("/api/billing/fake/" + session).with(as(admin, shop.id()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/billing/fake/%s/pay".formatted(session)).with(as(admin, shop.id())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/billing/fake/" + session).with(as(other.owner(), other.id()))).andExpect(status().isNotFound());
        mvc.perform(post("/api/billing/fake/%s/pay".formatted(session)).with(as(other.owner(), other.id())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/billing/fake/cs_fake_unknown").with(as(shop.owner(), shop.id()))).andExpect(status().isNotFound());
        mvc.perform(post("/api/billing/fake/%s/teleport".formatted(session)).with(as(shop.owner(), shop.id())))
                .andExpect(status().isNotFound());
        assertThat(planOf(shop)).isEqualTo("free");
    }

    @Test
    void thePublicDemoBusinessHasNoBilling() throws Exception {
        // Even if an operator gave the configured demo business an owner, billing stays off for it.
        long demo = fixture.business("Fieldstone Apparel", "fieldstone-apparel", "USD", "America/New_York");
        TestUser demoOwner = accounts.member("owner@fieldstone.test", demo, Role.OWNER);
        mvc.perform(billingGet(demo).with(as(demoOwner, demo))).andExpect(status().isNotFound());
        mvc.perform(checkoutPost(demo).with(as(demoOwner, demo))).andExpect(status().isNotFound());
        mvc.perform(portalPost(demo).with(as(demoOwner, demo))).andExpect(status().isNotFound());
        // Anonymous visitors never reach billing.
        mvc.perform(billingGet(demo)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_subscriptions", Long.class)).isZero();
    }

    @Test
    void checkoutRefusesUnknownPlansAndBusinessesAlreadyOnPro() throws Exception {
        mvc.perform(json(post("/api/businesses/%d/billing/checkout".formatted(shop.id())), "{\"plan\":\"free\"}")
                .with(as(shop.owner(), shop.id()))).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/businesses/%d/billing/checkout".formatted(shop.id())), "{\"plan\":\"gold\"}")
                .with(as(shop.owner(), shop.id()))).andExpect(status().isBadRequest());
        subscribe(shop);
        mvc.perform(checkoutPost(shop.id()).with(as(shop.owner(), shop.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This business is already on the Pro plan. Use Manage billing to change it."));
    }

    @Test
    void returnAddressesComeFromConfigurationOnly() throws Exception {
        String session = checkout(shop);
        String answer = body(mvc.perform(get("/api/billing/fake/" + session).with(as(shop.owner(), shop.id()))).andReturn());
        assertThat((String) read(answer, "$.successUrl")).endsWith("/settings/billing?checkout=success");
        assertThat((String) read(answer, "$.cancelUrl")).endsWith("/settings/billing?checkout=canceled");
        // A second checkout reuses the business's customer.
        checkout(shop);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fake_billing_objects WHERE kind = 'customer'", Long.class)).isEqualTo(1);
    }

    /** Authenticated requests without any CSRF header. */
    static final class TestAccountsNoCsrf {
        static org.springframework.test.web.servlet.request.RequestPostProcessor as(TestUser user, long businessId) {
            var auth = com.oussamaksantini.insightstudio.testsupport.TestAccounts.as(user);
            return request -> {
                request.addHeader("X-Business-Id", Long.toString(businessId));
                return auth.postProcessRequest(request);
            };
        }
    }
}
