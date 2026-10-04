package com.oussamaksantini.insightstudio.billing;

import com.oussamaksantini.insightstudio.billing.BillingProvider.InvalidWebhookException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The Stripe webhook signature scheme, used by both providers (docs/billing-contract.md §5): header
 * {@code Stripe-Signature: t=<unix seconds>,v1=<hex>[,v1=...]}, where each {@code v1} is the
 * HMAC-SHA256 of {@code "<t>.<raw body>"} keyed with the endpoint secret (the whole {@code whsec_...}
 * string). A signature is valid when any {@code v1} matches (constant-time) and {@code t} is within
 * {@link #TOLERANCE} of now; other schemes ({@code v0}, ...) are ignored.
 */
public final class StripeSignature {

    public static final String HEADER = "Stripe-Signature";
    public static final Duration TOLERANCE = Duration.ofMinutes(5);

    private StripeSignature() {
    }

    /** Throws {@link InvalidWebhookException} unless {@code header} signs {@code body} with {@code secret}. */
    public static void verify(byte[] body, String header, String secret, Clock clock) {
        if (header == null || header.isBlank()) {
            throw new InvalidWebhookException("Missing " + HEADER + " header.");
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String item : header.split(",")) {
            int equals = item.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = item.substring(0, equals).strip();
            String value = item.substring(equals + 1).strip();
            if (key.equals("t") && timestamp == null) {
                try {
                    timestamp = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new InvalidWebhookException("Malformed " + HEADER + " timestamp.");
                }
            } else if (key.equals("v1")) {
                signatures.add(value.toLowerCase(Locale.ROOT));
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new InvalidWebhookException("No v1 signature with a timestamp in " + HEADER + ".");
        }
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestamp) > TOLERANCE.toSeconds()) {
            throw new InvalidWebhookException("The webhook's timestamp is outside the tolerance.");
        }
        byte[] expected = hex(body, timestamp, secret).getBytes(StandardCharsets.US_ASCII);
        boolean match = false;
        for (String signature : signatures) {
            // Every candidate is compared, in constant time.
            match |= MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII));
        }
        if (!match) {
            throw new InvalidWebhookException("The webhook signature does not match.");
        }
    }

    /** The {@code Stripe-Signature} header for {@code body} signed at {@code timestamp} (the fake provider, tests). */
    public static String header(byte[] body, long timestamp, String secret) {
        return "t=" + timestamp + ",v1=" + hex(body, timestamp, secret);
    }

    private static String hex(byte[] body, long timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }
}
