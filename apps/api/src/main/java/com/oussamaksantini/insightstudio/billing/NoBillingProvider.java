package com.oussamaksantini.insightstudio.billing;

import java.util.Optional;

/** {@code insight.billing.provider=none}: billing is off; nothing ever reaches a provider. */
final class NoBillingProvider implements BillingProvider {

    @Override
    public String name() {
        return BillingPlans.NONE;
    }

    @Override
    public String createCustomer(long businessId, String businessName, String idempotencyKey) {
        throw off();
    }

    @Override
    public CheckoutSession createCheckout(long businessId, String customerId, Plan plan, String successUrl, String cancelUrl,
            String idempotencyKey) {
        throw off();
    }

    @Override
    public void expireCheckout(String checkoutId) {
        throw off();
    }

    @Override
    public String createPortal(long businessId, String customerId, String returnUrl) {
        throw off();
    }

    @Override
    public Optional<SubscriptionState> fetchSubscription(String subscriptionId) {
        throw off();
    }

    @Override
    public Optional<CheckoutState> fetchCheckout(String checkoutId) {
        throw off();
    }

    @Override
    public void cancelSubscription(String subscriptionId) {
        throw off();
    }

    @Override
    public WebhookEvent verifyWebhook(byte[] rawBody, String signatureHeader) {
        throw new InvalidWebhookException("Billing is off.");
    }

    private static BillingUnavailableException off() {
        return new BillingUnavailableException("Billing is off (insight.billing.provider=none).");
    }
}
