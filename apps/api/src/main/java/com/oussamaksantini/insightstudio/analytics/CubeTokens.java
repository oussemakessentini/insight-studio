package com.oussamaksantini.insightstudio.analytics;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mints the short-lived HS256 JWTs the API sends to Cube. The only business claim is
 * {@code businessId}, which callers take from the resolved membership
 * ({@code CurrentBusiness}), never from request input. Cube verifies the same way in
 * {@code services/analytics/security.js}.
 */
final class CubeTokens {

    /** Token lifetime: long enough for one request (including Cube's "Continue wait" polling). */
    static final Duration LIFETIME = Duration.ofSeconds(60);
    /** Shortest secret accepted (HS256 wants at least 256 bits of key); Cube enforces the same. */
    static final int MIN_SECRET_LENGTH = 32;

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String HEADER = ENCODER.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final byte[] secret;
    private final Clock clock;

    CubeTokens(String secret, Clock clock) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                    "The Cube API secret must be at least %d characters.".formatted(MIN_SECRET_LENGTH));
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /** A token for {@code businessId}, valid for {@link #LIFETIME}. */
    String sign(long businessId) {
        if (businessId <= 0) {
            throw new IllegalArgumentException("businessId must be positive");
        }
        long now = clock.instant().getEpochSecond();
        String payload = "{\"businessId\":%d,\"iat\":%d,\"exp\":%d}".formatted(businessId, now, now + LIFETIME.toSeconds());
        String unsigned = HEADER + "." + ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return unsigned + "." + ENCODER.encodeToString(hmac(unsigned));
    }

    /**
     * Verifies a token signed with this secret and returns its {@code businessId}. Used by tests
     * (the stub Cube) to check what the API sends; Cube has its own verifier.
     *
     * @throws IllegalArgumentException when the token is malformed, tampered with, expired or has
     *     no positive {@code businessId}
     */
    long verify(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Malformed token");
        }
        JsonNode header = decode(parts[0]);
        if (!"HS256".equals(header.path("alg").asString(null))) {
            throw new IllegalArgumentException("Unexpected algorithm");
        }
        byte[] expected = hmac(parts[0] + "." + parts[1]);
        byte[] actual;
        try {
            actual = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed signature");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("Invalid signature");
        }
        JsonNode claims = decode(parts[1]);
        JsonNode exp = claims.path("exp");
        if (!exp.isIntegralNumber() || exp.asLong() <= clock.instant().getEpochSecond()) {
            throw new IllegalArgumentException("Token expired");
        }
        JsonNode businessId = claims.path("businessId");
        if (!businessId.isIntegralNumber() || businessId.asLong() <= 0) {
            throw new IllegalArgumentException("Token has no business");
        }
        return businessId.asLong();
    }

    private static JsonNode decode(String segment) {
        try {
            JsonNode node = JSON.readTree(DECODER.decode(segment));
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("Malformed token");
            }
            return node;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed token", e);
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
