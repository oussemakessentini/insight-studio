package com.oussamaksantini.insightstudio.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oussamaksantini.insightstudio.billing.BillingProperties.Limits;
import com.oussamaksantini.insightstudio.billing.BillingProperties.PlanSettings;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Plan configuration checks and the effective plan rules (docs/billing-contract.md §1). */
class BillingPlansTest {

    private static BillingPlans plans(String provider, Limits free, Limits pro) {
        return new BillingPlans(new BillingProperties(provider, Map.of(
                "free", new PlanSettings("Free", "Free", null, free),
                "pro", new PlanSettings("Pro", "$29 / month", null, pro)), null, null, null));
    }

    private static final Limits FREE = new Limits(3, 2, 10, 3, 10);
    private static final Limits PRO = new Limits(25, 50, 200, 50, 500);

    @Test
    void limitsAboveTheAbsoluteCapsOrNegativeOrMissingStopTheStartup() {
        assertThatThrownBy(() -> plans("fake", FREE, new Limits(25, 50, 201, 50, 500)))
                .hasMessageContaining("charts (201) exceeds the absolute cap of 200");
        assertThatThrownBy(() -> plans("fake", FREE, new Limits(25, 50, 200, 51, 500)))
                .hasMessageContaining("dashboards (51) exceeds the absolute cap of 50");
        assertThatThrownBy(() -> plans("fake", new Limits(-1, 2, 10, 3, 10), PRO)).hasMessageContaining("must not be negative");
        assertThatThrownBy(() -> plans("fake", new Limits(3, null, 10, 3, 10), PRO)).hasMessageContaining("stores is missing");
        assertThatThrownBy(() -> new BillingPlans(new BillingProperties("fake",
                Map.of("free", new PlanSettings("Free", "Free", null, FREE)), null, null, null)))
                .hasMessageContaining("plan 'pro' is missing");
        assertThatThrownBy(() -> plans("paypal", FREE, PRO)).hasMessageContaining("must be fake, stripe or none");
    }

    @Test
    void theEffectivePlanFollowsTheSubscriptionStatus() {
        BillingPlans plans = plans("fake", FREE, PRO);
        for (String status : new String[] {"active", "trialing", "past_due"}) {
            assertThat(plans.effective("fake", "pro", status).key()).as(status).isEqualTo("pro");
        }
        for (String status : new String[] {"none", "incomplete", "incomplete_expired", "unpaid", "canceled", "paused"}) {
            assertThat(plans.effective("fake", "pro", status).key()).as(status).isEqualTo("free");
        }
        // Another provider's subscription, an unknown plan, or billing off: Free.
        assertThat(plans.effective("stripe", "pro", "active").key()).isEqualTo("free");
        assertThat(plans.effective("fake", "gold", "active").key()).isEqualTo("free");
        assertThat(plans("none", FREE, PRO).effective("none", "pro", "active").key()).isEqualTo("free");
        assertThat(plans.all()).extracting(Plan::key).containsExactly("free", "pro");
        assertThat(plans.upgradeFor(plans.free(), PlanResource.STORES)).map(Plan::key).hasValue("pro");
        assertThat(plans.upgradeFor(plans.find("pro").orElseThrow(), PlanResource.STORES)).isEmpty();
        assertThat(plans.forPrice("price_fake_pro")).map(Plan::key).hasValue("pro");
    }

    @Test
    void retryDelaysDoubleFromThirtySecondsToAnHour() {
        BillingProperties.Worker worker = new BillingProperties.Worker(true, Duration.ofSeconds(2), Duration.ofMinutes(2),
                Duration.ofSeconds(30), Duration.ofHours(1), 6);
        assertThat(worker.retryDelay(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(worker.retryDelay(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(worker.retryDelay(7)).isEqualTo(Duration.ofSeconds(1920));
        assertThat(worker.retryDelay(8)).isEqualTo(Duration.ofHours(1));
        assertThat(worker.retryDelay(100)).isEqualTo(Duration.ofHours(1));
    }
}
