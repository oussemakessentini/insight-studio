package com.oussamaksantini.insightstudio.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.testsupport.ApiInstance;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Forwarded headers over real HTTP. By default they are ignored, so a client cannot dodge the
 * per-IP limits by inventing {@code X-Forwarded-For} values. With the test client's address
 * configured as a trusted proxy, the forwarded client address and scheme are used.
 */
class TrustedProxyIntegrationTest extends PostgresIntegrationTest {

    private static final int IP_LIMIT = RateLimit.SIGN_IN_PER_IP.max();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcConnectionDetails database;

    @BeforeEach
    void clean() {
        new SqlFixture(jdbc).clear();
    }

    private static HttpResponse<String> failSignIn(HttpApiClient client, int n, String forwardedFor) throws Exception {
        return client.postJson("/api/auth/sign-in",
                "{\"email\":\"nobody%d@example.com\",\"password\":\"wrong password\"}".formatted(n),
                "X-Forwarded-For", forwardedFor);
    }

    @Test
    void spoofedForwardedForDoesNotEscapeThePerIpLimit() throws Exception {
        try (HttpApiClient client = new HttpApiClient(port)) {
            client.get("/api/session");
            for (int i = 0; i < IP_LIMIT; i++) {
                assertThat(failSignIn(client, i, "203.0.113." + i).statusCode()).isEqualTo(401);
            }
            assertThat(failSignIn(client, 99, "198.51.100.99").statusCode()).isEqualTo(429);
            // And X-Forwarded-Proto from an untrusted client does not turn on HSTS.
            HttpResponse<String> session = client.get("/api/session", "X-Forwarded-Proto", "https");
            assertThat(session.headers().firstValue("Strict-Transport-Security")).isEmpty();
        }
    }

    @Test
    void behindATrustedProxyEachForwardedClientHasItsOwnLimit() throws Exception {
        Map<String, Object> trustLocalhost = Map.of("insight.security.trusted-proxies", "127.0.0.1,::1");
        try (ApiInstance behindProxy = ApiInstance.start(database, trustLocalhost); HttpApiClient proxy = behindProxy.client()) {
            proxy.get("/api/session");
            for (int i = 0; i < IP_LIMIT; i++) {
                assertThat(failSignIn(proxy, i, "203.0.113.1").statusCode()).isEqualTo(401);
            }
            assertThat(failSignIn(proxy, 99, "203.0.113.1").statusCode()).isEqualTo(429);
            // A spoofed left-most entry does not help: the proxy appended the real address.
            assertThat(failSignIn(proxy, 98, "1.1.1.1, 203.0.113.1").statusCode()).isEqualTo(429);
            // Another client behind the same proxy is not affected.
            assertThat(failSignIn(proxy, 97, "203.0.113.2").statusCode()).isEqualTo(401);

            // The proxy terminated TLS: HSTS is sent.
            HttpResponse<String> session = proxy.get("/api/session", "X-Forwarded-Proto", "https");
            assertThat(session.headers().firstValue("Strict-Transport-Security")).isPresent();
        }
    }
}
