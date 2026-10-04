package com.oussamaksantini.insightstudio.billing;

import com.oussamaksantini.insightstudio.billing.BillingProvider.CheckoutState;
import com.oussamaksantini.insightstudio.billing.BillingProvider.InvalidWebhookException;
import com.oussamaksantini.insightstudio.billing.BillingProvider.SubscriptionState;
import com.oussamaksantini.insightstudio.billing.BillingProvider.WebhookEvent;
import java.time.Instant;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads Stripe-shaped JSON (events, subscriptions, checkout sessions), as sent by Stripe and by the
 * fake provider. Only references and states are kept: names, emails and addresses are never read.
 * Both the older (top-level {@code current_period_end}, {@code invoice.subscription}) and newer API
 * shapes ({@code items.data[].current_period_end}, {@code invoice.parent.subscription_details}) are read.
 */
final class StripeObjects {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ID = 255;

    private StripeObjects() {
    }

    static JsonNode read(byte[] body) {
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (JacksonException e) {
            throw new InvalidWebhookException("The event is not valid JSON.");
        }
        if (node == null || !node.isObject()) {
            throw new InvalidWebhookException("The event is not a JSON object.");
        }
        return node;
    }

    /** The references of a verified event. */
    static WebhookEvent event(JsonNode event) {
        String id = text(event, "id");
        String type = text(event, "type");
        if (id == null || type == null || id.length() > MAX_ID || type.length() > 100) {
            throw new InvalidWebhookException("The event has no usable id or type.");
        }
        JsonNode object = event.path("data").path("object");
        String objectType = text(object, "object");
        if (objectType != null && objectType.length() > 50) {
            objectType = objectType.substring(0, 50);
        }
        String objectId = id(object.get("id"));
        String customer = id(object.get("customer"));
        String subscription = null;
        Long business = null;
        if ("subscription".equals(objectType)) {
            subscription = objectId;
            business = businessId(object.path("metadata"));
        } else if ("checkout.session".equals(objectType)) {
            subscription = id(object.get("subscription"));
            business = businessId(object.path("metadata"));
            if (business == null) {
                business = number(text(object, "client_reference_id"));
            }
        } else if ("invoice".equals(objectType)) {
            JsonNode details = object.path("parent").path("subscription_details");
            subscription = id(object.get("subscription"));
            if (subscription == null) {
                subscription = id(details.get("subscription"));
            }
            business = businessId(object.path("subscription_details").path("metadata"));
            if (business == null) {
                business = businessId(details.path("metadata"));
            }
        }
        return new WebhookEvent(id, type, event.path("livemode").asBoolean(false), objectType, objectId, subscription,
                customer, business);
    }

    static SubscriptionState subscription(JsonNode object) {
        JsonNode item = object.path("items").path("data").path(0);
        Instant periodEnd = epoch(item.get("current_period_end"));
        if (periodEnd == null) {
            periodEnd = epoch(object.get("current_period_end"));
        }
        String status = text(object, "status");
        // Newer API versions may schedule a cancellation with cancel_at instead of cancel_at_period_end.
        Instant cancelAt = epoch(object.get("cancel_at"));
        boolean cancelAtPeriodEnd = object.path("cancel_at_period_end").asBoolean(false)
                || (cancelAt != null && !"canceled".equals(status));
        if (periodEnd == null) {
            periodEnd = cancelAt;
        }
        return new SubscriptionState(id(object.get("id")), id(object.get("customer")), status,
                id(item.path("price").get("id")), businessId(object.path("metadata")), cancelAtPeriodEnd, periodEnd,
                epoch(object.get("canceled_at")), object.path("livemode").asBoolean(false));
    }

    static CheckoutState checkout(JsonNode object) {
        Long business = businessId(object.path("metadata"));
        if (business == null) {
            business = number(text(object, "client_reference_id"));
        }
        return new CheckoutState(id(object.get("id")), id(object.get("subscription")), id(object.get("customer")), business);
    }

    /** An id field, or the {@code id} of an expanded object; {@code null} when absent or too long. */
    static String id(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            return id(node.get("id"));
        }
        if (!node.isString()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() || value.length() > MAX_ID ? null : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() && !value.asString().isBlank() ? value.asString().strip() : null;
    }

    private static Long businessId(JsonNode metadata) {
        return number(text(metadata, "business_id"));
    }

    private static Long number(String value) {
        if (value == null) {
            return null;
        }
        try {
            long id = Long.parseLong(value);
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant epoch(JsonNode node) {
        return node != null && node.isNumber() && node.asLong() > 0 ? Instant.ofEpochSecond(node.asLong()) : null;
    }
}
