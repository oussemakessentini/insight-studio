package com.oussamaksantini.insightstudio.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.testsupport.ApiInstance;
import com.oussamaksantini.insightstudio.testsupport.CapturingPasswordResetNotifier;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rate limits on sign-up, sign-in and password recovery, over real HTTP. The limits live in
 * PostgreSQL: they hold across API instances and restarts, and concurrent requests cannot slip
 * past them.
 */
class RateLimitIntegrationTest extends PostgresIntegrationTest {

    private static final String EMAIL = "limited@example.com";
    private static final String NEW_PASSWORD = "a brand new passphrase";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcConnectionDetails database;

    @Autowired
    CapturingPasswordResetNotifier notifier;

    @BeforeEach
    void clean() {
        new SqlFixture(jdbc).clear();
        new TestAccounts(jdbc).user(EMAIL);
        notifier.clear();
    }

    private HttpApiClient browser() throws Exception {
        return browser(port);
    }

    private static HttpApiClient browser(int port) throws Exception {
        HttpApiClient client = new HttpApiClient(port);
        client.get("/api/session");
        return client;
    }

    private static void expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("%s %s -> %s", response.request().method(), response.request().uri(),
                response.body()).isEqualTo(status);
    }

    private static void expectLimited(HttpResponse<String> response, RateLimit limit) {
        expect(response, 429);
        long retryAfter = Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
        assertThat(retryAfter).isBetween(1L, limit.window().toSeconds());
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
    }

    private static HttpResponse<String> signUp(HttpApiClient client, String email) throws Exception {
        return client.postJson("/api/auth/sign-up",
                "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"X\"}".formatted(email, TestAccounts.PASSWORD));
    }

    private static HttpResponse<String> signIn(HttpApiClient client, String password) throws Exception {
        return client.postJson("/api/auth/sign-in", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, password));
    }

    private static HttpResponse<String> forgot(HttpApiClient client, String email) throws Exception {
        return client.postJson("/api/auth/password/forgot", "{\"email\":\"%s\"}".formatted(email));
    }

    @Test
    void signUpIsLimitedPerIpCountingEveryAttempt() throws Exception {
        int max = RateLimit.SIGN_UP_PER_IP.max();
        try (HttpApiClient client = browser()) {
            expect(signUp(client, "first@example.com"), 202);
            // Invalid attempts and existing addresses count too: probing emails costs attempts.
            expect(signUp(client, EMAIL), 202);
            expect(signUp(client, "not-an-email"), 400);
            for (int i = 3; i < max; i++) {
                expect(signUp(client, EMAIL.toUpperCase()), 202);
            }
            expectLimited(signUp(client, "new@example.com"), RateLimit.SIGN_UP_PER_IP);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = 'new@example.com'", Long.class)).isZero();
    }

    @Test
    void passwordResetRequestsAreLimitedPerAddressWithoutRevealingAccounts() throws Exception {
        int perAddress = RateLimit.RESET_EMAIL_PER_ADDRESS.max();
        try (HttpApiClient client = browser()) {
            for (int i = 0; i < perAddress + 1; i++) {
                expect(forgot(client, EMAIL), 202);
                expect(forgot(client, "ghost@example.com"), 202);
            }
            // Only the first few requests for the address send mail; the answer never changes.
            assertThat(notifier.sent()).hasSize(perAddress);
            // Other addresses are unaffected.
            new TestAccounts(jdbc).user("other@example.com");
            expect(forgot(client, "other@example.com"), 202);
            assertThat(notifier.sent()).hasSize(perAddress + 1);
        }
    }

    @Test
    void passwordResetRequestsAreLimitedPerIp() throws Exception {
        int max = RateLimit.RESET_REQUEST_PER_IP.max();
        try (HttpApiClient client = browser()) {
            for (int i = 0; i < max; i++) {
                expect(forgot(client, "someone" + i + "@example.com"), 202);
            }
            expectLimited(forgot(client, EMAIL), RateLimit.RESET_REQUEST_PER_IP);
            assertThat(notifier.sent()).isEmpty();
        }
    }

    @Test
    void resetTokenGuessingIsLimitedPerIp() throws Exception {
        int max = RateLimit.RESET_CONFIRM_PER_IP.max();
        try (HttpApiClient client = browser()) {
            expect(forgot(client, EMAIL), 202);
            String token = notifier.last().orElseThrow().token();
            for (int i = 0; i < max; i++) {
                expect(client.postJson("/api/auth/password/reset",
                        "{\"token\":\"guess-%d\",\"newPassword\":\"%s\"}".formatted(i, NEW_PASSWORD)), 400);
            }
            // Even the right token waits: guessing cannot continue from this address.
            expectLimited(client.postJson("/api/auth/password/reset",
                    "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, NEW_PASSWORD)), RateLimit.RESET_CONFIRM_PER_IP);
        }
    }

    @Test
    void failuresCountedOnOneInstanceBlockEveryInstanceAndSurviveARestart() throws Exception {
        try (ApiInstance other = ApiInstance.start(database); HttpApiClient there = browser(other.port())) {
            for (int i = 0; i < RateLimit.SIGN_IN_PER_EMAIL.max(); i++) {
                expect(signIn(there, "wrong password " + i), 401);
            }
            expectLimited(signIn(there, TestAccounts.PASSWORD), RateLimit.SIGN_IN_PER_EMAIL);
        }
        // That instance is gone; this one (and any restarted one) still enforces the limit.
        try (HttpApiClient here = browser()) {
            expectLimited(signIn(here, TestAccounts.PASSWORD), RateLimit.SIGN_IN_PER_EMAIL);
        }
    }

    @Test
    void theWindowSlides() throws Exception {
        try (HttpApiClient client = browser()) {
            for (int i = 0; i < RateLimit.SIGN_IN_PER_EMAIL.max(); i++) {
                expect(signIn(client, "wrong password " + i), 401);
            }
            expect(signIn(client, TestAccounts.PASSWORD), 429);

            // Ten of the fifteen minutes have passed: about five minutes left.
            jdbc.update("UPDATE rate_limit_hits SET hit_at = hit_at - interval '10 minutes'");
            HttpResponse<String> limited = signIn(client, TestAccounts.PASSWORD);
            expect(limited, 429);
            assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(240L, 300L);

            // The window has passed.
            jdbc.update("UPDATE rate_limit_hits SET hit_at = hit_at - interval '6 minutes'");
            expect(signIn(client, TestAccounts.PASSWORD), 200);
            // A successful sign-in clears the email's failures (the IP's stay counted).
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_hits WHERE bucket LIKE 'sign-in:email:%'", Long.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_hits WHERE bucket LIKE 'sign-in:ip:%'", Long.class))
                    .isEqualTo(RateLimit.SIGN_IN_PER_EMAIL.max());
        }
    }

    @Test
    void concurrentRequestsCannotExceedTheLimit() throws Exception {
        int max = RateLimit.SIGN_UP_PER_IP.max();
        int requests = max * 3;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try (HttpApiClient client = browser()) {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                // Invalid on purpose (no bcrypt): the limit is taken before validation.
                calls.add(() -> signUp(client, "not-an-email").statusCode());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : pool.invokeAll(calls)) {
                statuses.add(result.get());
            }
            assertThat(statuses).filteredOn(s -> s == 400).hasSize(max);
            assertThat(statuses).filteredOn(s -> s == 429).hasSize(requests - max);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void bucketsStoreNeitherEmailsNorAddresses() throws Exception {
        try (HttpApiClient client = browser()) {
            expect(signIn(client, "wrong password"), 401);
            expect(forgot(client, EMAIL), 202);
        }
        List<String> buckets = jdbc.queryForList("SELECT bucket FROM rate_limit_hits", String.class);
        assertThat(buckets).isNotEmpty().allSatisfy(bucket -> {
            assertThat(bucket).matches("[a-z-]+:[a-z]+:[0-9a-f]{64}");
            assertThat(bucket).doesNotContain("@").doesNotContain("127.0.0.1");
        });
    }
}
