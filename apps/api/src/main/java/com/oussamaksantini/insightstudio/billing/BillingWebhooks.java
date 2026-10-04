package com.oussamaksantini.insightstudio.billing;

import com.oussamaksantini.insightstudio.billing.BillingProvider.InvalidWebhookException;
import com.oussamaksantini.insightstudio.billing.BillingProvider.WebhookEvent;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Receives webhooks (docs/billing-contract.md §5, steps 1 and 2): verifies the signature, then records
 * the event's references once in {@code billing_events} ({@code ON CONFLICT DO NOTHING}: a duplicate
 * delivery is a no-op) and returns at once. {@link BillingEventWorker} processes it later. Nothing is
 * stored for an invalid webhook, and the payload itself is never stored or logged.
 */
@Component
public class BillingWebhooks {

    private static final Logger log = LoggerFactory.getLogger(BillingWebhooks.class);
    /** Larger bodies are refused before anything else (Stripe events are a few kilobytes). */
    static final int MAX_BODY = 512 * 1024;

    private final BillingProvider provider;
    private final NamedParameterJdbcTemplate jdbc;

    BillingWebhooks(BillingProvider provider, NamedParameterJdbcTemplate jdbc) {
        this.provider = provider;
        this.jdbc = jdbc;
    }

    /**
     * @param providerName the {@code {provider}} of the path: only the configured provider's webhooks exist (404 otherwise)
     * @return whether the event is new ({@code false}: a duplicate delivery, nothing changed)
     * @throws ApiException 404 for another provider or billing off, 400 for an invalid webhook
     */
    public boolean receive(String providerName, byte[] body, String signature) {
        if (provider.name().equals(BillingPlans.NONE) || !provider.name().equals(providerName)) {
            throw ApiException.notFound("Not found.");
        }
        if (body == null || body.length == 0 || body.length > MAX_BODY) {
            throw ApiException.badRequest("Invalid webhook.");
        }
        WebhookEvent event;
        try {
            event = provider.verifyWebhook(body, signature);
        } catch (InvalidWebhookException e) {
            log.warn("Refused a {} webhook: {}", providerName, e.getMessage());
            throw ApiException.badRequest("Invalid webhook.");
        }
        int inserted = jdbc.update("""
                INSERT INTO billing_events (provider, event_id, event_type, object_type, object_id, subscription_id,
                    customer_id, business_id)
                VALUES (:provider, :eventId, :type, :objectType, :objectId, :subscriptionId, :customerId, :businessId)
                ON CONFLICT (provider, event_id) DO NOTHING
                """, new MapSqlParameterSource()
                        .addValue("provider", provider.name())
                        .addValue("eventId", event.id())
                        .addValue("type", event.type())
                        .addValue("objectType", event.objectType())
                        .addValue("objectId", event.objectId())
                        .addValue("subscriptionId", event.subscriptionId())
                        .addValue("customerId", event.customerId())
                        .addValue("businessId", event.businessId()));
        if (inserted == 0) {
            log.info("Duplicate {} webhook {} ({}) ignored.", providerName, event.id(), event.type());
        } else {
            log.debug("Recorded {} webhook {} ({}).", providerName, event.id(), event.type());
        }
        return inserted == 1;
    }
}
