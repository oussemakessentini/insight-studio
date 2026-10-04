package com.oussamaksantini.insightstudio.billing;

import com.oussamaksantini.insightstudio.account.AccountProperties;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A local stand-in for Stripe (docs/billing-contract.md §4, §7), for development and tests: no network,
 * no real payment. Its customers, checkout sessions, subscriptions and portal sessions are Stripe-shaped
 * JSON in {@code fake_billing_objects}, so they survive restarts and are shared by every API instance.
 *
 * <p>Its checkout and portal "pages" are web pages ({@code /billing/fake/checkout/{id}},
 * {@code /billing/fake/portal/{id}}) that call {@link FakeBillingController}. Each action changes the
 * fake's state and, once that is committed, emits the matching events signed exactly like Stripe
 * ({@code Stripe-Signature}, the fake's own secret) into the real webhook pipeline
 * ({@link BillingWebhooks#receive}, which verifies the signature), where the worker processes them like
 * Stripe's. Never used with the {@code prod} profile (startup fails).
 */
public class FakeBillingProvider implements BillingProvider {

    private static final Logger log = LoggerFactory.getLogger(FakeBillingProvider.class);
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    /** How far a period runs (Stripe bills monthly; a fixed length keeps the fake simple). */
    static final long PERIOD_DAYS = 30;
    private static final int KEPT_DELIVERIES = 200;

    /** A signed event as the fake emitted it (tests replay, duplicate and reorder these). */
    public record Delivery(String eventId, String type, byte[] body, String signature) {
    }

    /** What an action did: the page to go to next ({@code null}: stay) and the events it emitted. */
    record Outcome(String redirectUrl, List<ObjectNode> events) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectProvider<BillingWebhooks> webhooks;
    private final BillingPlans plans;
    private final AccountProperties web;
    private final String webhookSecret;
    private final boolean deliver;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Deque<Delivery> deliveries = new ConcurrentLinkedDeque<>();

    FakeBillingProvider(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions,
            ObjectProvider<BillingWebhooks> webhooks, BillingPlans plans, AccountProperties web,
            BillingProperties.Fake settings, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.webhooks = webhooks;
        this.plans = plans;
        this.web = web;
        this.webhookSecret = settings.webhookSecret();
        this.deliver = settings.deliverWebhooks();
        this.clock = clock;
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("insight.billing.fake.webhook-secret must not be empty");
        }
    }

    @Override
    public String name() {
        return BillingPlans.FAKE;
    }

    /** The secret the fake signs its webhooks with (tests sign their own replays with it). */
    public String webhookSecret() {
        return webhookSecret;
    }

    // ---------------------------------------------------------------- BillingProvider

    @Override
    public String createCustomer(long businessId, String businessName, String idempotencyKey) {
        return transactions.execute(status -> idempotent(idempotencyKey, () -> {
            ObjectNode customer = object("cus_fake_", "customer");
            customer.putObject("metadata").put("business_id", Long.toString(businessId));
            insert("customer", customer);
            return customer.get("id").asString();
        }));
    }

    /**
     * Like Stripe: the first request with a key creates the object and records it; a later request with the
     * same key returns that object instead of creating another one.
     */
    private String idempotent(String key, java.util.function.Supplier<String> create) {
        if (key == null || key.isBlank()) {
            return create.get();
        }
        String id = "idem_" + key;
        // Serialises requests with the same key (a concurrent retry waits for the first to finish).
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))", Map.of("k", id),
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null);
        Optional<String> earlier = jdbc.queryForList(
                "SELECT data ->> 'object_id' FROM fake_billing_objects WHERE id = :id AND kind = 'idempotency'",
                Map.of("id", id), String.class).stream().findFirst();
        if (earlier.isPresent()) {
            return earlier.get();
        }
        String created = create.get();
        ObjectNode record = StripeObjects.JSON.createObjectNode();
        record.put("id", id);
        record.put("object_id", created);
        insert("idempotency", record);
        return created;
    }

    @Override
    public CheckoutSession createCheckout(long businessId, String customerId, Plan plan, String successUrl, String cancelUrl,
            String idempotencyKey) {
        String id = transactions.execute(status -> idempotent(idempotencyKey,
                () -> newCheckout(businessId, customerId, plan, successUrl, cancelUrl)));
        return new CheckoutSession(id, web.page("/billing/fake/checkout/" + id));
    }

    @Override
    public void expireCheckout(String checkoutId) {
        transactions.executeWithoutResult(status -> load(checkoutId, "checkout", true).ifPresent(checkout -> {
            if ("open".equals(checkout.path("status").asString())) {
                checkout.put("status", "expired");
                save(checkout);
            }
        }));
    }

    private String newCheckout(long businessId, String customerId, Plan plan, String successUrl, String cancelUrl) {
        ObjectNode checkout = object("cs_fake_", "checkout.session");
        checkout.put("mode", "subscription");
        checkout.put("status", "open");
        checkout.put("customer", customerId);
        checkout.put("client_reference_id", Long.toString(businessId));
        checkout.putObject("metadata").put("business_id", Long.toString(businessId));
        checkout.putNull("subscription");
        checkout.put("success_url", successUrl);
        checkout.put("cancel_url", cancelUrl);
        checkout.put("plan", plan.key());
        checkout.put("price", plans.priceId(plan));
        checkout.put("declined", false);
        insert("checkout", checkout);
        return checkout.get("id").asString();
    }

    @Override
    public String createPortal(long businessId, String customerId, String returnUrl) {
        ObjectNode portal = object("bps_fake_", "billing_portal.session");
        portal.put("customer", customerId);
        portal.put("return_url", returnUrl);
        portal.put("business_id", Long.toString(businessId));
        insert("portal", portal);
        return web.page("/billing/fake/portal/" + portal.get("id").asString());
    }

    @Override
    public Optional<SubscriptionState> fetchSubscription(String subscriptionId) {
        return load(subscriptionId, "subscription", false).map(StripeObjects::subscription);
    }

    @Override
    public Optional<CheckoutState> fetchCheckout(String checkoutId) {
        return load(checkoutId, "checkout", false).map(StripeObjects::checkout);
    }

    @Override
    public void cancelSubscription(String subscriptionId) {
        Outcome outcome = transactions.execute(status -> {
            Optional<ObjectNode> found = load(subscriptionId, "subscription", true);
            if (found.isEmpty() || BillingPlans.ENDED.contains(found.get().path("status").asString())) {
                return new Outcome(null, List.of());   // missing or already ended: done
            }
            ObjectNode subscription = found.get();
            cancelNow(subscription);
            return new Outcome(null, List.of(event("customer.subscription.deleted", subscription)));
        });
        deliverAfterCommit(outcome.events());
    }

    @Override
    public WebhookEvent verifyWebhook(byte[] rawBody, String signatureHeader) {
        StripeSignature.verify(rawBody, signatureHeader, webhookSecret, clock);
        return StripeObjects.event(StripeObjects.read(rawBody));
    }

    // ---------------------------------------------------------------- the fake's pages

    /** A checkout or portal session and the state the fake pages show. */
    record Session(String id, String kind, long businessId, JsonNode data, JsonNode subscription) {
    }

    /** The checkout or portal session {@code id} (404 when unknown). */
    Session session(String id) {
        if (id == null || !id.matches("^(cs|bps)_fake_[A-Za-z0-9]{1,40}$")) {
            throw ApiException.notFound("Test session not found.");
        }
        String kind = id.startsWith("cs_") ? "checkout" : "portal";
        ObjectNode data = load(id, kind, false).orElseThrow(() -> ApiException.notFound("Test session not found."));
        long businessId = Long.parseLong(kind.equals("checkout")
                ? data.path("metadata").path("business_id").asString() : data.path("business_id").asString());
        JsonNode subscription = kind.equals("checkout")
                ? Optional.ofNullable(StripeObjects.id(data.get("subscription")))
                        .flatMap(s -> load(s, "subscription", false)).orElse(null)
                : latestSubscription(data.path("customer").asString(), false).orElse(null);
        return new Session(id, kind, businessId, data, subscription);
    }

    /** Checkout actions: {@code pay}, {@code decline}, {@code cancel}. */
    static final List<String> CHECKOUT_ACTIONS = List.of("pay", "decline", "cancel");
    /** Portal actions (the last one is an extra for development: the current period ends now). */
    static final List<String> PORTAL_ACTIONS = List.of("cancel-at-period-end", "resume", "cancel-now", "fail-renewal",
            "unpaid", "pay-outstanding", "end-period");

    /** Runs a page action; its events are emitted once the change is committed. */
    Outcome act(String sessionId, String action) {
        Outcome outcome = transactions.execute(status -> {
            Session session = session(sessionId);
            return session.kind().equals("checkout") ? checkoutAction(session, action) : portalAction(session, action);
        });
        deliverAfterCommit(outcome.events());
        return outcome;
    }

    private Outcome checkoutAction(Session session, String action) {
        ObjectNode checkout = load(session.id(), "checkout", true).orElseThrow();
        String state = checkout.path("status").asString();
        switch (action) {
            case "cancel" -> {
                return new Outcome(checkout.path("cancel_url").asString(), List.of());
            }
            case "pay", "decline" -> {
                if (state.equals("expired")) {
                    throw ApiException.conflict("This test checkout has expired.");
                }
                if (!state.equals("open")) {
                    throw ApiException.conflict("This test checkout is already complete.");
                }
                boolean pay = action.equals("pay");
                List<ObjectNode> events = new ArrayList<>();
                String subscriptionId = StripeObjects.id(checkout.get("subscription"));
                ObjectNode subscription;
                if (subscriptionId == null) {
                    subscription = newSubscription(checkout, pay ? "active" : "incomplete");
                    checkout.put("subscription", subscription.get("id").asString());
                    events.add(event("customer.subscription.created", subscription));
                } else {
                    subscription = load(subscriptionId, "subscription", true).orElseThrow();
                    if (pay) {
                        subscription.put("status", "active");
                        renew(subscription);
                        save(subscription);
                        events.add(event("customer.subscription.updated", subscription));
                    }
                }
                ObjectNode invoice = invoice(subscription);
                checkout.put("declined", !pay);
                if (pay) {
                    checkout.put("status", "complete");
                    events.add(event("invoice.paid", invoice));
                    events.add(event("checkout.session.completed", checkout));
                } else {
                    events.add(event("invoice.payment_failed", invoice));
                }
                save(checkout);
                return new Outcome(pay ? checkout.path("success_url").asString() : null, events);
            }
            default -> throw ApiException.notFound("Unknown test checkout action.");
        }
    }

    private Outcome portalAction(Session session, String action) {
        if (!PORTAL_ACTIONS.contains(action)) {
            throw ApiException.notFound("Unknown test portal action.");
        }
        ObjectNode subscription = latestSubscription(session.data().path("customer").asString(), true)
                .orElseThrow(() -> ApiException.conflict("There is no subscription to manage."));
        if (BillingPlans.ENDED.contains(subscription.path("status").asString())) {
            throw ApiException.conflict("This subscription has ended. Start a new one from the Billing page.");
        }
        List<ObjectNode> events = new ArrayList<>();
        switch (action) {
            case "cancel-at-period-end" -> {
                subscription.put("cancel_at_period_end", true);
                events.add(event("customer.subscription.updated", subscription));
            }
            case "resume" -> {
                subscription.put("cancel_at_period_end", false);
                if (subscription.path("status").asString().equals("paused")) {
                    subscription.put("status", "active");
                    events.add(event("customer.subscription.resumed", subscription));
                } else {
                    events.add(event("customer.subscription.updated", subscription));
                }
            }
            case "cancel-now" -> {
                cancelNow(subscription);
                events.add(event("customer.subscription.deleted", subscription));
            }
            case "fail-renewal" -> {
                subscription.put("status", "past_due");
                events.add(event("invoice.payment_failed", invoice(subscription)));
                events.add(event("customer.subscription.updated", subscription));
            }
            case "unpaid" -> {
                subscription.put("status", "unpaid");
                events.add(event("customer.subscription.updated", subscription));
            }
            case "pay-outstanding" -> {
                subscription.put("status", "active");
                renew(subscription);
                events.add(event("invoice.paid", invoice(subscription)));
                events.add(event("customer.subscription.updated", subscription));
            }
            case "end-period" -> {
                if (subscription.path("cancel_at_period_end").asBoolean(false)) {
                    cancelNow(subscription);
                    events.add(event("customer.subscription.deleted", subscription));
                } else {
                    renew(subscription);
                    events.add(event("invoice.paid", invoice(subscription)));
                    events.add(event("customer.subscription.updated", subscription));
                }
            }
            default -> throw new IllegalStateException(action);
        }
        save(subscription);
        return new Outcome(null, events);
    }

    // ---------------------------------------------------------------- state

    private ObjectNode newSubscription(ObjectNode checkout, String status) {
        ObjectNode subscription = object("sub_fake_", "subscription");
        subscription.put("customer", checkout.path("customer").asString());
        subscription.put("status", status);
        subscription.put("cancel_at_period_end", false);
        subscription.putNull("canceled_at");
        subscription.put("livemode", false);
        subscription.put("plan_key", checkout.path("plan").asString());
        subscription.putObject("metadata").put("business_id", checkout.path("metadata").path("business_id").asString());
        ObjectNode item = subscription.putObject("items").putArray("data").addObject();
        item.putObject("price").put("id", checkout.path("price").asString());
        renew(subscription);
        insert("subscription", subscription);
        return subscription;
    }

    private void renew(ObjectNode subscription) {
        long end = clock.instant().plusSeconds(PERIOD_DAYS * 86_400).getEpochSecond();
        ((ObjectNode) subscription.path("items").path("data").path(0)).put("current_period_end", end);
    }

    private void cancelNow(ObjectNode subscription) {
        subscription.put("status", "canceled");
        subscription.put("cancel_at_period_end", false);
        subscription.put("canceled_at", clock.instant().getEpochSecond());
        save(subscription);
    }

    private ObjectNode invoice(JsonNode subscription) {
        ObjectNode invoice = object("in_fake_", "invoice");
        invoice.put("customer", subscription.path("customer").asString());
        invoice.put("subscription", subscription.path("id").asString());
        ObjectNode details = invoice.putObject("parent").putObject("subscription_details");
        details.put("subscription", subscription.path("id").asString());
        details.set("metadata", subscription.path("metadata").deepCopy());
        return invoice;
    }

    private ObjectNode object(String prefix, String type) {
        ObjectNode node = StripeObjects.JSON.createObjectNode();
        node.put("id", prefix + randomId());
        node.put("object", type);
        node.put("created", clock.instant().getEpochSecond());
        return node;
    }

    private ObjectNode event(String type, ObjectNode object) {
        ObjectNode event = object("evt_fake_", "event");
        event.put("api_version", "fake");
        event.put("livemode", false);
        event.put("type", type);
        event.putObject("data").set("object", object.deepCopy());
        return event;
    }

    private Optional<ObjectNode> latestSubscription(String customerId, boolean lock) {
        return jdbc.queryForList("""
                SELECT data::text FROM fake_billing_objects
                WHERE kind = 'subscription' AND data ->> 'customer' = :c
                ORDER BY created_at DESC, id DESC LIMIT 1
                """ + (lock ? " FOR UPDATE" : ""), Map.of("c", customerId), String.class)
                .stream().findFirst().map(text -> (ObjectNode) StripeObjects.JSON.readTree(text));
    }

    private Optional<ObjectNode> load(String id, String kind, boolean lock) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc.queryForList("SELECT data::text FROM fake_billing_objects WHERE id = :id AND kind = :kind"
                        + (lock ? " FOR UPDATE" : ""), Map.of("id", id, "kind", kind), String.class)
                .stream().findFirst().map(text -> (ObjectNode) StripeObjects.JSON.readTree(text));
    }

    private void insert(String kind, ObjectNode data) {
        jdbc.update("INSERT INTO fake_billing_objects (id, kind, data) VALUES (:id, :kind, CAST(:data AS jsonb))",
                Map.of("id", data.get("id").asString(), "kind", kind, "data", data.toString()));
    }

    private void save(ObjectNode data) {
        jdbc.update("UPDATE fake_billing_objects SET data = CAST(:data AS jsonb), updated_at = now() WHERE id = :id",
                Map.of("id", data.get("id").asString(), "data", data.toString()));
    }

    private String randomId() {
        StringBuilder id = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            id.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return id.toString();
    }

    // ---------------------------------------------------------------- webhooks

    /** The events the fake emitted most recently, oldest first (tests). */
    public List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    /** Signs an event body now, as the fake (and Stripe) would. */
    public String sign(byte[] body) {
        return StripeSignature.header(body, clock.instant().getEpochSecond(), webhookSecret);
    }

    private void deliverAfterCommit(List<ObjectNode> events) {
        if (events.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(events);
                }
            });
        } else {
            deliver(events);
        }
    }

    private void deliver(List<ObjectNode> events) {
        for (ObjectNode event : events) {
            byte[] body = event.toString().getBytes(StandardCharsets.UTF_8);
            Delivery delivery = new Delivery(event.get("id").asString(), event.get("type").asString(), body, sign(body));
            deliveries.addLast(delivery);
            while (deliveries.size() > KEPT_DELIVERIES) {
                deliveries.pollFirst();
            }
            if (!deliver) {
                continue;
            }
            try {
                webhooks.getObject().receive(name(), body, delivery.signature());
            } catch (RuntimeException e) {
                log.warn("The fake billing provider could not deliver {} {}: {}", delivery.type(), delivery.eventId(),
                        e.getClass().getSimpleName());
            }
        }
    }

    /** For the pages: an epoch second as an instant. */
    static Instant instant(JsonNode node) {
        return node != null && node.isNumber() ? Instant.ofEpochSecond(node.asLong()) : null;
    }
}
