package com.oussamaksantini.insightstudio.billing;

import java.time.Instant;
import java.util.Optional;

/**
 * A payment provider (docs/billing-contract.md §4): {@link FakeBillingProvider}, {@link StripeBillingProvider}
 * or {@link NoBillingProvider}. Every call that reaches the provider throws
 * {@link BillingUnavailableException} when it cannot be completed (network, provider error), so callers
 * answer {@code 503} or retry later.
 *
 * <p>Calls go over the network: callers never make them inside a database transaction (they would hold
 * row locks and a pooled connection for as long as the provider takes). Calls that create something
 * carry an idempotency key recorded beforehand in {@code billing_operations}, so a retry after a timeout
 * returns what the first attempt may already have created.
 */
public interface BillingProvider {

    /** {@code fake}, {@code stripe} or {@code none}: also the {@code {provider}} of the webhook path. */
    String name();

    /** Creates the provider customer of a business; returns its id. */
    String createCustomer(long businessId, String businessName, String idempotencyKey);

    /** Starts a checkout of {@code plan} for the customer; returns its id and the page to send the owner to. */
    CheckoutSession createCheckout(long businessId, String customerId, Plan plan, String successUrl, String cancelUrl,
            String idempotencyKey);

    /**
     * Expires an open checkout session so it can no longer be paid. Idempotent: a session that is already
     * expired, complete or unknown is done.
     */
    void expireCheckout(String checkoutId);

    /** Opens the customer's billing portal; returns the page to send the owner to. */
    String createPortal(long businessId, String customerId, String returnUrl);

    /** The subscription's current state, or empty when the provider does not know it. */
    Optional<SubscriptionState> fetchSubscription(String subscriptionId);

    /** A checkout session's references, or empty when the provider does not know it. */
    Optional<CheckoutState> fetchCheckout(String checkoutId);

    /** Cancels the subscription now, without proration. Idempotent: an already canceled or missing one is done. */
    void cancelSubscription(String subscriptionId);

    /**
     * Verifies a webhook's signature and reads its references.
     *
     * @throws InvalidWebhookException for a missing, malformed, wrong or stale signature, or an unreadable event
     */
    WebhookEvent verifyWebhook(byte[] rawBody, String signatureHeader);

    /**
     * A subscription as the provider reports it now.
     *
     * @param businessId {@code metadata.business_id}, or {@code null} when absent or not a number
     */
    record SubscriptionState(String id, String customerId, String status, String priceId, Long businessId,
            boolean cancelAtPeriodEnd, Instant currentPeriodEnd, Instant canceledAt, boolean livemode) {
    }

    /** A checkout session's references. */
    record CheckoutState(String id, String subscriptionId, String customerId, Long businessId) {
    }

    /** A checkout session just created: its id and the page to send the owner to. */
    record CheckoutSession(String id, String url) {
    }

    /**
     * A verified webhook event's references (never its payload).
     *
     * @param objectType the type of {@code data.object} (e.g. {@code subscription}, {@code checkout.session})
     */
    record WebhookEvent(String id, String type, boolean livemode, String objectType, String objectId,
            String subscriptionId, String customerId, Long businessId) {
    }

    /**
     * The provider could not be reached or answered with an error. Its message never holds a secret.
     * {@link #retrySameKey()}: whether the outcome is unknown (timeout, connection error) or the provider
     * did not record the request (a conflicting concurrent request, a rate limit), so a retry must reuse the
     * same idempotency key; {@code false} when the provider answered with an error it keeps for that key
     * (Stripe replays the first result of a key, errors included), so a retry needs a new key.
     */
    class BillingUnavailableException extends RuntimeException {

        private final boolean retrySameKey;

        public BillingUnavailableException(String message) {
            this(message, true);
        }

        public BillingUnavailableException(String message, boolean retrySameKey) {
            super(message);
            this.retrySameKey = retrySameKey;
        }

        public BillingUnavailableException(String message, Throwable cause) {
            super(message, cause);
            this.retrySameKey = true;
        }

        public boolean retrySameKey() {
            return retrySameKey;
        }
    }

    /** A webhook that must not be accepted (400, nothing stored). */
    class InvalidWebhookException extends RuntimeException {

        public InvalidWebhookException(String message) {
            super(message);
        }
    }
}
