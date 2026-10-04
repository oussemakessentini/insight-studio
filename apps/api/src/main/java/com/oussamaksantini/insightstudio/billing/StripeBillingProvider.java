package com.oussamaksantini.insightstudio.billing;

import com.oussamaksantini.insightstudio.billing.BillingProperties.Stripe;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Stripe, through its REST API (docs/billing-contract.md §4), in <b>test mode only</b>: the API refuses
 * to start unless the secret key is a test key ({@code sk_test_...} or {@code rk_test_...}), and events
 * or subscriptions with {@code livemode: true} are refused.
 *
 * <p>Requests are form-encoded, authenticated with {@code Authorization: Bearer <key>}, pinned to
 * {@code Stripe-Version} (configuration), and every POST carries an {@code Idempotency-Key}. Errors are
 * logged with Stripe's error type and code only; the key, request bodies and customer data never are.
 */
final class StripeBillingProvider implements BillingProvider {

    private static final Logger log = LoggerFactory.getLogger(StripeBillingProvider.class);
    /** Stripe object ids are letters, digits and underscores; anything else never reaches a URL path. */
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_]{1,255}$");

    private final RestClient http;
    private final String webhookSecret;
    private final BillingPlans plans;
    private final Clock clock;

    StripeBillingProvider(Stripe settings, BillingPlans plans, Clock clock) {
        String key = settings.secretKey() == null ? "" : settings.secretKey().strip();
        if (!key.startsWith("sk_test_") && !key.startsWith("rk_test_")) {
            throw new IllegalStateException("Billing with Stripe runs in test mode only: STRIPE_SECRET_KEY "
                    + "(insight.billing.stripe.secret-key) must be a test key starting with sk_test_ or rk_test_.");
        }
        String secret = settings.webhookSecret() == null ? "" : settings.webhookSecret().strip();
        if (!secret.startsWith("whsec_")) {
            throw new IllegalStateException("STRIPE_WEBHOOK_SECRET (insight.billing.stripe.webhook-secret) must be the "
                    + "webhook endpoint's signing secret (whsec_...).");
        }
        if (settings.apiVersion() == null || settings.apiVersion().isBlank()) {
            throw new IllegalStateException("insight.billing.stripe.api-version must pin a Stripe API version.");
        }
        this.webhookSecret = secret;
        this.plans = plans;
        this.clock = clock;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(settings.readTimeout());
        this.http = RestClient.builder()
                .baseUrl(settings.apiBase().replaceAll("/+$", ""))
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + key)
                .defaultHeader("Stripe-Version", settings.apiVersion().strip())
                .build();
    }

    @Override
    public String name() {
        return BillingPlans.STRIPE;
    }

    @Override
    public String createCustomer(long businessId, String businessName, String idempotencyKey) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("name", businessName);
        form.put("metadata[business_id]", Long.toString(businessId));
        return requireId(post("/v1/customers", form, idempotencyKey), "customer");
    }

    @Override
    public String createCheckout(long businessId, String customerId, Plan plan, String successUrl, String cancelUrl,
            String idempotencyKey) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("mode", "subscription");
        form.put("line_items[0][price]", plans.priceId(plan));
        form.put("line_items[0][quantity]", "1");
        form.put("customer", customerId);
        form.put("client_reference_id", Long.toString(businessId));
        form.put("metadata[business_id]", Long.toString(businessId));
        form.put("subscription_data[metadata][business_id]", Long.toString(businessId));
        form.put("success_url", successUrl);
        form.put("cancel_url", cancelUrl);
        return requireUrl(post("/v1/checkout/sessions", form, idempotencyKey), "checkout session");
    }

    @Override
    public String createPortal(long businessId, String customerId, String returnUrl) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("customer", customerId);
        form.put("return_url", returnUrl);
        return requireUrl(post("/v1/billing_portal/sessions", form, "insight-portal-" + java.util.UUID.randomUUID()),
                "billing portal session");
    }

    @Override
    public Optional<SubscriptionState> fetchSubscription(String subscriptionId) {
        return get("/v1/subscriptions/" + checkId(subscriptionId)).map(StripeObjects::subscription);
    }

    @Override
    public Optional<CheckoutState> fetchCheckout(String checkoutId) {
        return get("/v1/checkout/sessions/" + checkId(checkoutId)).map(StripeObjects::checkout);
    }

    @Override
    public void cancelSubscription(String subscriptionId) {
        Answer answer = send(HttpMethod.DELETE, "/v1/subscriptions/" + checkId(subscriptionId), null, null);
        if (answer.ok() || answer.missing()) {
            return;   // canceled now, or Stripe does not know it: nothing left to cancel
        }
        if (answer.status() >= 400 && answer.status() < 500) {
            // e.g. already canceled: done if Stripe says it has ended.
            Optional<SubscriptionState> state = fetchSubscription(subscriptionId);
            if (state.isEmpty() || BillingPlans.ENDED.contains(state.get().status())) {
                return;
            }
        }
        throw answer.failure("cancel subscription");
    }

    @Override
    public WebhookEvent verifyWebhook(byte[] rawBody, String signatureHeader) {
        StripeSignature.verify(rawBody, signatureHeader, webhookSecret, clock);
        WebhookEvent event = StripeObjects.event(StripeObjects.read(rawBody));
        if (event.livemode()) {
            throw new InvalidWebhookException("Live-mode events are refused: billing runs in Stripe test mode only.");
        }
        return event;
    }

    // ---------------------------------------------------------------- HTTP

    private record Answer(int status, JsonNode body, String errorType, String errorCode) {

        boolean ok() {
            return status >= 200 && status < 300;
        }

        boolean missing() {
            return status == 404 || "resource_missing".equals(errorCode);
        }

        BillingUnavailableException failure(String action) {
            return new BillingUnavailableException("Stripe could not %s: HTTP %d%s".formatted(action, status,
                    errorType == null ? "" : " (" + errorType + (errorCode == null ? "" : "/" + errorCode) + ")"));
        }
    }

    private JsonNode post(String path, Map<String, String> form, String idempotencyKey) {
        Answer answer = send(HttpMethod.POST, path, form, idempotencyKey);
        if (!answer.ok()) {
            throw answer.failure("answer POST " + path);
        }
        return answer.body();
    }

    private Optional<JsonNode> get(String path) {
        Answer answer = send(HttpMethod.GET, path, null, null);
        if (answer.missing()) {
            return Optional.empty();
        }
        if (!answer.ok()) {
            throw answer.failure("answer GET " + path.replaceAll("/[A-Za-z0-9_]+$", "/{id}"));
        }
        if (answer.body().path("livemode").asBoolean(false)) {
            throw new BillingUnavailableException("Stripe returned a live-mode object; billing runs in test mode only.");
        }
        return Optional.of(answer.body());
    }

    private Answer send(HttpMethod method, String path, Map<String, String> form, String idempotencyKey) {
        try {
            RestClient.RequestBodySpec request = http.method(method).uri(path).accept(MediaType.APPLICATION_JSON);
            if (idempotencyKey != null) {
                request.header("Idempotency-Key", idempotencyKey);
            }
            if (form != null) {
                request.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(encode(form));
            }
            return request.exchange((req, res) -> {
                int status = res.getStatusCode().value();
                String text = StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8);
                JsonNode body = parse(text);
                JsonNode error = body.path("error");
                String type = error.path("type").isString() ? error.path("type").asString() : null;
                String code = error.path("code").isString() ? error.path("code").asString() : null;
                if (status >= 400) {
                    log.warn("Stripe {} {} answered HTTP {} ({}/{}).", method, path.replaceAll("/[A-Za-z0-9_]+$", "/{id}"),
                            status, type, code);
                }
                return new Answer(status, body, type, code);
            });
        } catch (RestClientException e) {
            throw new BillingUnavailableException("Stripe is unreachable: " + e.getClass().getSimpleName(), e);
        }
    }

    private static JsonNode parse(String text) {
        try {
            JsonNode node = StripeObjects.JSON.readTree(text == null || text.isBlank() ? "{}" : text);
            return node == null ? StripeObjects.JSON.createObjectNode() : node;
        } catch (JacksonException e) {
            return StripeObjects.JSON.createObjectNode();
        }
    }

    static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String checkId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Not a Stripe object id");
        }
        return id;
    }

    private static String requireId(JsonNode body, String what) {
        String id = StripeObjects.id(body.get("id"));
        if (id == null) {
            throw new BillingUnavailableException("Stripe returned a " + what + " without an id.");
        }
        return id;
    }

    private static String requireUrl(JsonNode body, String what) {
        JsonNode url = body.get("url");
        if (url == null || !url.isString() || !url.asString().startsWith("https://")) {
            throw new BillingUnavailableException("Stripe returned a " + what + " without an https URL.");
        }
        return url.asString();
    }
}
