package com.oussamaksantini.insightstudio.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CubeTokensTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-test";
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CubeTokens tokens = new CubeTokens(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void signsAnHs256TokenWithBusinessIdAndSixtySecondExpiry() {
        String token = tokens.sign(42);

        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        JsonNode header = decode(parts[0]);
        JsonNode claims = decode(parts[1]);
        assertThat(header.get("alg").asString()).isEqualTo("HS256");
        assertThat(claims.get("businessId").asLong()).isEqualTo(42);
        assertThat(claims.get("iat").asLong()).isEqualTo(NOW.getEpochSecond());
        assertThat(claims.get("exp").asLong()).isEqualTo(NOW.getEpochSecond() + 60);
        assertThat(claims.propertyNames()).containsExactlyInAnyOrder("businessId", "iat", "exp");
        assertThat(tokens.verify(token)).isEqualTo(42);
    }

    @Test
    void rejectsATamperedSignature() {
        String token = tokens.sign(42);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertThatThrownBy(() -> tokens.verify(tampered)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsATamperedPayload() {
        String[] parts = tokens.sign(42).split("\\.");
        String otherBusiness = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"businessId\":43,\"iat\":%d,\"exp\":%d}".formatted(NOW.getEpochSecond(), NOW.getEpochSecond() + 60))
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> tokens.verify(parts[0] + "." + otherBusiness + "." + parts[2]))
                .hasMessage("Invalid signature");
    }

    @Test
    void rejectsATokenSignedWithAnotherSecret() {
        CubeTokens other = new CubeTokens("another-secret-another-secret-another", Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> tokens.verify(other.sign(42))).hasMessage("Invalid signature");
    }

    @Test
    void rejectsAnExpiredToken() {
        String token = tokens.sign(42);
        CubeTokens later = new CubeTokens(SECRET, Clock.fixed(NOW.plus(Duration.ofSeconds(61)), ZoneOffset.UTC));

        assertThatThrownBy(() -> later.verify(token)).hasMessage("Token expired");
    }

    @Test
    void rejectsMalformedTokens() {
        assertThatThrownBy(() -> tokens.verify(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.verify("a.b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.verify("!!.??.**")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesWeakSecretsAndNonPositiveBusinesses() {
        assertThatThrownBy(() -> new CubeTokens("short", Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CubeTokens(null, Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.sign(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static JsonNode decode(String segment) {
        return JSON.readTree(Base64.getUrlDecoder().decode(segment));
    }
}
