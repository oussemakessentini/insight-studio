package com.oussamaksantini.insightstudio.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.testsupport.ApiInstance;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Provider selection at startup (docs/billing-contract.md §4): billing off ({@code none}: endpoints 404,
 * limits still enforced), the fake refused with the {@code prod} profile, Stripe refused without a
 * test-mode key. Each case is a separate API instance on the test database.
 */
class BillingProviderSelectionIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcConnectionDetails database;

    long business;

    @BeforeEach
    void setUp() {
        new SqlFixture(jdbc).clear();
        business = new SqlFixture(jdbc).business("Off Co", "off-co", "USD", "UTC");
        new TestAccounts(jdbc).member("owner@off.test", business, Role.OWNER);
    }

    private static HttpApiClient signIn(ApiInstance api) throws Exception {
        HttpApiClient client = api.client();
        client.get("/api/session");
        HttpResponse<String> signedIn = client.postJson("/api/auth/sign-in",
                "{\"email\":\"owner@off.test\",\"password\":\"%s\"}".formatted(TestAccounts.PASSWORD));
        assertThat(signedIn.statusCode()).as(signedIn.body()).isEqualTo(200);
        return client;
    }

    @Test
    void withBillingOffTheEndpointsAreNotFoundButFreeLimitsStillApply() throws Exception {
        Map<String, Object> properties = Map.of("insight.billing.provider", "none",
                "insight.billing.plans.free.limits.stores", 1);
        try (ApiInstance api = ApiInstance.start(database, properties); HttpApiClient client = signIn(api)) {
            assertThat(client.get("/api/billing/plans").statusCode()).isEqualTo(404);
            assertThat(client.get("/api/businesses/%d/billing".formatted(business)).statusCode()).isEqualTo(404);
            assertThat(client.postJson("/api/businesses/%d/billing/checkout".formatted(business), "{\"plan\":\"pro\"}")
                    .statusCode()).isEqualTo(404);
            assertThat(client.postJson("/api/billing/webhooks/fake", "{}").statusCode()).isEqualTo(404);
            String store = "{\"code\":\"%s\",\"name\":\"Store\"}";
            assertThat(client.postJson("/api/stores", store.formatted("A"), "X-Business-Id", Long.toString(business))
                    .statusCode()).isEqualTo(201);
            HttpResponse<String> refused = client.postJson("/api/stores", store.formatted("B"), "X-Business-Id", Long.toString(business));
            assertThat(refused.statusCode()).isEqualTo(409);
            assertThat(refused.body()).contains("\"code\":\"plan_limit\"").contains("\"upgradeAvailable\":false")
                    .contains("The Free plan allows 1 store. Delete one first.");
        }
    }

    @Test
    void theFakeProviderIsRefusedWithTheProdProfile() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.profiles.active", "prod");
        properties.put("WEB_BASE_URL", "https://app.example.com");
        properties.put("MAIL_HOST", "smtp.example.com");
        properties.put("MAIL_FROM", "Insight Studio <no-reply@example.com>");
        properties.put("BILLING_PROVIDER", "fake");
        assertThatThrownBy(() -> ApiInstance.start(database, properties).close())
                .rootCause().hasMessageContaining("fake billing provider is for development and tests only");
    }

    @Test
    void stripeIsRefusedWithoutATestModeKey() {
        Map<String, Object> properties = Map.of("insight.billing.provider", "stripe",
                "insight.billing.stripe.secret-key", "sk_live_definitely_not_allowed",
                "insight.billing.stripe.webhook-secret", "whsec_x",
                "insight.billing.plans.pro.provider-price-id", "price_123");
        assertThatThrownBy(() -> ApiInstance.start(database, properties).close())
                .rootCause().hasMessageContaining("test mode only").hasMessageNotContaining("sk_live_definitely_not_allowed");
    }
}
