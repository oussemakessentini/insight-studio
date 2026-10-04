package com.oussamaksantini.insightstudio.billing;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code insight.billing.*} (docs/billing-api.md). Checked at startup by {@link BillingPlans} and the
 * selected provider: an invalid plan or provider setting stops the API.
 *
 * @param provider {@code fake} (default outside {@code prod}), {@code stripe} or {@code none} (billing off)
 * @param plans the plans by key; {@code free} (every business without a paid subscription) and
 *     {@code pro} are required
 * @param stripe the Stripe adapter's settings (only read when {@code provider=stripe})
 * @param fake the local fake provider's settings (only read when {@code provider=fake})
 * @param worker the webhook event and cancellation workers
 */
@ConfigurationProperties("insight.billing")
public record BillingProperties(
        @DefaultValue("fake") String provider,
        Map<String, PlanSettings> plans,
        @DefaultValue Stripe stripe,
        @DefaultValue Fake fake,
        @DefaultValue Worker worker) {

    public BillingProperties {
        plans = plans == null ? Map.of() : Map.copyOf(plans);
    }

    /**
     * One plan.
     *
     * @param name display name, e.g. {@code Pro}
     * @param priceDisplay price as shown, e.g. {@code $29 / month} ({@code Free} for the free plan)
     * @param providerPriceId the provider's price (Stripe {@code price_...}); paid plans only
     */
    public record PlanSettings(String name, String priceDisplay, String providerPriceId, @DefaultValue Limits limits) {
    }

    /** A plan's limits; {@code null} values are refused at startup. */
    public record Limits(Integer members, Integer stores, Integer charts, Integer dashboards, Integer importsPerMonth) {
    }

    /**
     * @param secretKey {@code STRIPE_SECRET_KEY}: a test-mode key ({@code sk_test_...} or {@code rk_test_...}) only
     * @param webhookSecret {@code STRIPE_WEBHOOK_SECRET}: the endpoint's signing secret ({@code whsec_...})
     * @param apiVersion the {@code Stripe-Version} every request pins
     * @param apiBase the API's address (tests point it at a local stub)
     * @param connectTimeout connection timeout of every call
     * @param readTimeout answer timeout of every call
     */
    public record Stripe(
            @DefaultValue("") String secretKey,
            @DefaultValue("") String webhookSecret,
            @DefaultValue("2025-03-31.basil") String apiVersion,
            @DefaultValue("https://api.stripe.com") String apiBase,
            @DefaultValue("PT5S") Duration connectTimeout,
            @DefaultValue("PT20S") Duration readTimeout) {
    }

    /**
     * @param webhookSecret the secret the fake signs its webhooks with (Stripe's scheme); not a real secret
     * @param deliverWebhooks whether the fake sends its events to the webhook pipeline (tests may replay them instead)
     */
    public record Fake(
            @DefaultValue("whsec_fake_insight_studio_local_only") String webhookSecret,
            @DefaultValue("true") boolean deliverWebhooks) {
    }

    /**
     * {@code insight.billing.worker.*}: both workers (webhook events, cancellations).
     *
     * @param enabled whether this instance runs them (every instance may; they share the work)
     * @param pollInterval how often they look for due work
     * @param lease how long claimed work belongs to one worker (a crashed worker's work is retried after it)
     * @param retryDelay wait after the first failure; it doubles after each further failure
     * @param maxRetryDelay the longest wait (work is retried until it succeeds)
     * @param alertAfterAttempts failures in a row from which each failure is logged as an error
     */
    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT2S") Duration pollInterval,
            @DefaultValue("PT2M") Duration lease,
            @DefaultValue("PT30S") Duration retryDelay,
            @DefaultValue("PT1H") Duration maxRetryDelay,
            @DefaultValue("6") int alertAfterAttempts) {

        public Worker {
            if (alertAfterAttempts < 1 || retryDelay.isNegative() || retryDelay.isZero()
                    || maxRetryDelay.compareTo(retryDelay) < 0 || lease.isNegative() || lease.isZero()) {
                throw new IllegalStateException("Invalid insight.billing.worker settings");
            }
        }

        /** The wait before the next attempt after {@code attempts} failures in a row. */
        public Duration retryDelay(int attempts) {
            Duration delay = retryDelay;
            for (int i = 1; i < attempts && delay.compareTo(maxRetryDelay) < 0; i++) {
                delay = delay.multipliedBy(2);
            }
            return delay.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : delay;
        }
    }
}
