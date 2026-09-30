package com.oussamaksantini.insightstudio.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.security.RateLimit;
import com.oussamaksantini.insightstudio.testsupport.CapturingPasswordResetNotifier;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Sign-up, sign-in, password change and recovery over real HTTP sessions: each
 * {@link HttpApiClient} is a separate browser.
 */
class AccountIntegrationTest extends PostgresIntegrationTest {

    private static final String EMAIL = "user@example.com";
    private static final String NEW_PASSWORD = "a brand new passphrase";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CapturingPasswordResetNotifier notifier;

    @BeforeEach
    void clean() {
        new SqlFixture(jdbc).clear();
        new TestAccounts(jdbc).user(EMAIL);
        notifier.clear();
    }

    private HttpApiClient browser() throws Exception {
        HttpApiClient client = new HttpApiClient(port);
        client.get("/api/session");
        return client;
    }

    private static HttpResponse<String> signIn(HttpApiClient client, String email, String password) throws Exception {
        return client.postJson("/api/auth/sign-in", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    private static void expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("%s -> %s", response.request().uri(), response.body()).isEqualTo(status);
    }

    private static String detail(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.detail");
    }

    private long tokens() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens", Long.class);
    }

    @Nested
    class SignUpAndSignIn {

        @Test
        void signUpValidation() throws Exception {
            try (HttpApiClient client = browser()) {
                String[] invalid = {
                    "{\"email\":\"not-an-email\",\"password\":\"long enough password\",\"displayName\":\"X\"}",
                    "{\"email\":\"new@example.com\",\"password\":\"short\",\"displayName\":\"X\"}",
                    "{\"email\":\"longpassword@example.com\",\"password\":\"longpassword@example.com\",\"displayName\":\"X\"}",
                    "{\"email\":\"new@example.com\",\"password\":\"" + "x".repeat(73) + "\",\"displayName\":\"X\"}",
                    "{\"email\":\"new@example.com\",\"password\":\"" + "x".repeat(129) + "\",\"displayName\":\"X\"}",
                    "{\"email\":\"new@example.com\",\"password\":\"long enough password\"}",
                    "{\"email\":\"new@example.com\",\"password\":\"long enough password\",\"displayName\":\"" + "n".repeat(101) + "\"}",
                };
                for (String body : invalid) {
                    HttpResponse<String> response = client.postJson("/api/auth/sign-up", body);
                    expect(response, 400);
                    assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
                }
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isEqualTo(1);
                expect(client.get("/api/businesses"), 401);
            }
        }

        @Test
        void duplicateEmailIsAConflictIgnoringCase() throws Exception {
            try (HttpApiClient client = browser()) {
                HttpResponse<String> response = client.postJson("/api/auth/sign-up",
                        "{\"email\":\" USER@example.COM \",\"password\":\"long enough password\",\"displayName\":\"X\"}");
                expect(response, 409);
                expect(client.get("/api/businesses"), 401);
            }
        }

        @Test
        void unknownEmailAndWrongPasswordLookTheSame() throws Exception {
            try (HttpApiClient client = browser()) {
                HttpResponse<String> unknown = signIn(client, "ghost@example.com", TestAccounts.PASSWORD);
                HttpResponse<String> wrong = signIn(client, EMAIL, "wrong password here");
                HttpResponse<String> tooLong = signIn(client, EMAIL, "y".repeat(100));
                for (HttpResponse<String> response : java.util.List.of(unknown, wrong, tooLong)) {
                    expect(response, 401);
                    assertThat(detail(response)).isEqualTo("Invalid email or password.");
                }
                assertThat(unknown.body().replaceAll("\"instance\":\"[^\"]*\"", ""))
                        .isEqualTo(wrong.body().replaceAll("\"instance\":\"[^\"]*\"", ""));
                expect(signIn(client, " User@Example.com ", TestAccounts.PASSWORD), 200);
                assertThat(jdbc.queryForObject("SELECT last_sign_in_at IS NOT NULL FROM users", Boolean.class)).isTrue();
            }
        }

        @Test
        void fiveFailuresPerEmailThen429() throws Exception {
            try (HttpApiClient client = browser()) {
                for (int i = 0; i < RateLimit.SIGN_IN_PER_EMAIL.max(); i++) {
                    expect(signIn(client, EMAIL, "wrong password " + i), 401);
                }
                // Even the right password is refused while the limit holds.
                HttpResponse<String> limited = signIn(client, EMAIL, TestAccounts.PASSWORD);
                expect(limited, 429);
                long retryAfter = Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow());
                assertThat(retryAfter).isBetween(1L, RateLimit.SIGN_IN_PER_EMAIL.window().toSeconds());
                assertThat(limited.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
                expect(client.get("/api/businesses"), 401);
                // Other accounts from the same IP are not blocked yet.
                new TestAccounts(jdbc).user("other@example.com");
                expect(signIn(client, "other@example.com", TestAccounts.PASSWORD), 200);
            }
        }

        @Test
        void twentyFailuresPerIpThen429() throws Exception {
            try (HttpApiClient client = browser()) {
                for (int i = 0; i < RateLimit.SIGN_IN_PER_IP.max(); i++) {
                    expect(signIn(client, "nobody" + i + "@example.com", "wrong password"), 401);
                }
                expect(signIn(client, EMAIL, TestAccounts.PASSWORD), 429);
            }
        }
    }

    @Nested
    class Recovery {

        @Test
        void forgotForUnknownEmailIs202AndCreatesNothing() throws Exception {
            try (HttpApiClient client = browser()) {
                expect(client.postJson("/api/auth/password/forgot", "{\"email\":\"ghost@example.com\"}"), 202);
                expect(client.postJson("/api/auth/password/forgot", "{\"email\":\"\"}"), 202);
                assertThat(tokens()).isZero();
                assertThat(notifier.sent()).isEmpty();
            }
        }

        @Test
        void resetTokenIsHashedSingleUseAndSignsOutEverySession() throws Exception {
            try (HttpApiClient signedIn = browser(); HttpApiClient other = browser()) {
                expect(signIn(signedIn, EMAIL, TestAccounts.PASSWORD), 200);
                expect(signedIn.get("/api/businesses"), 200);

                expect(other.postJson("/api/auth/password/forgot", "{\"email\":\"USER@example.com\"}"), 202);
                CapturingPasswordResetNotifier.Sent sent = notifier.last().orElseThrow();
                assertThat(sent.email()).isEqualTo(EMAIL);
                assertThat(sent.link()).startsWith("http://localhost:5173/reset-password?token=");
                String token = sent.token();
                assertThat(token).matches("[A-Za-z0-9_-]{43}");
                // Only the hash is stored.
                assertThat(jdbc.queryForObject("SELECT token_sha256 FROM password_reset_tokens", String.class))
                        .isEqualTo(AccountService.sha256(token)).isNotEqualTo(token);
                assertThat(jdbc.queryForObject(
                        "SELECT expires_at BETWEEN now() + interval '29 minutes' AND now() + interval '31 minutes' FROM password_reset_tokens",
                        Boolean.class)).isTrue();

                // A policy error does not spend the token.
                expect(other.postJson("/api/auth/password/reset", "{\"token\":\"%s\",\"newPassword\":\"short\"}".formatted(token)), 400);
                expect(other.postJson("/api/auth/password/reset", "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, EMAIL)), 400);

                expect(other.postJson("/api/auth/password/reset",
                        "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, NEW_PASSWORD)), 204);
                HttpResponse<String> again = other.postJson("/api/auth/password/reset",
                        "{\"token\":\"%s\",\"newPassword\":\"another new passphrase\"}".formatted(token));
                expect(again, 400);
                assertThat(detail(again)).isEqualTo("This reset link is invalid or has expired.");

                // The session that existed before the reset is signed out.
                expect(signedIn.get("/api/businesses"), 401);
                expect(signIn(other, EMAIL, TestAccounts.PASSWORD), 401);
                expect(signIn(other, EMAIL, NEW_PASSWORD), 200);
            }
        }

        @Test
        void expiredOrUnknownTokensAreRejected() throws Exception {
            try (HttpApiClient client = browser()) {
                expect(client.postJson("/api/auth/password/forgot", "{\"email\":\"" + EMAIL + "\"}"), 202);
                String token = notifier.last().orElseThrow().token();
                jdbc.update("UPDATE password_reset_tokens SET expires_at = now() - interval '1 minute'");
                HttpResponse<String> expired = client.postJson("/api/auth/password/reset",
                        "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, NEW_PASSWORD));
                expect(expired, 400);
                assertThat(detail(expired)).isEqualTo("This reset link is invalid or has expired.");
                expect(client.postJson("/api/auth/password/reset",
                        "{\"token\":\"made-up\",\"newPassword\":\"%s\"}".formatted(NEW_PASSWORD)), 400);
                expect(client.postJson("/api/auth/password/reset", "{\"newPassword\":\"%s\"}".formatted(NEW_PASSWORD)), 400);
                expect(signIn(client, EMAIL, TestAccounts.PASSWORD), 200);
            }
        }

        @Test
        void aNewRequestInvalidatesTheOlderLink() throws Exception {
            try (HttpApiClient client = browser()) {
                expect(client.postJson("/api/auth/password/forgot", "{\"email\":\"" + EMAIL + "\"}"), 202);
                String first = notifier.last().orElseThrow().token();
                expect(client.postJson("/api/auth/password/forgot", "{\"email\":\"" + EMAIL + "\"}"), 202);
                String second = notifier.last().orElseThrow().token();
                expect(client.postJson("/api/auth/password/reset",
                        "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(first, NEW_PASSWORD)), 400);
                expect(client.postJson("/api/auth/password/reset",
                        "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(second, NEW_PASSWORD)), 204);
            }
        }
    }

    @Nested
    class PasswordChange {

        @Test
        void keepsTheCurrentSessionAndSignsOutOthers() throws Exception {
            try (HttpApiClient current = browser(); HttpApiClient other = browser()) {
                expect(signIn(current, EMAIL, TestAccounts.PASSWORD), 200);
                expect(signIn(other, EMAIL, TestAccounts.PASSWORD), 200);
                String before = current.cookie(HttpApiClient.SESSION_COOKIE);

                HttpResponse<String> wrong = current.postJson("/api/auth/password/change",
                        "{\"currentPassword\":\"not my password\",\"newPassword\":\"%s\"}".formatted(NEW_PASSWORD));
                expect(wrong, 400);
                expect(current.postJson("/api/auth/password/change",
                        "{\"currentPassword\":\"%s\",\"newPassword\":\"short\"}".formatted(TestAccounts.PASSWORD)), 400);

                expect(current.postJson("/api/auth/password/change",
                        "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(TestAccounts.PASSWORD, NEW_PASSWORD)), 204);
                assertThat(current.cookie(HttpApiClient.SESSION_COOKIE)).isNotEqualTo(before);
                expect(current.get("/api/businesses"), 200);
                HttpResponse<String> session = current.get("/api/session");
                assertThat((Boolean) JsonPath.read(session.body(), "$.authenticated")).isTrue();
                expect(other.get("/api/businesses"), 401);

                expect(signIn(other, EMAIL, TestAccounts.PASSWORD), 401);
                expect(signIn(other, EMAIL, NEW_PASSWORD), 200);
            }
        }

        @Test
        void needsASession() throws Exception {
            try (HttpApiClient client = browser()) {
                expect(client.postJson("/api/auth/password/change",
                        "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(TestAccounts.PASSWORD, NEW_PASSWORD)), 401);
            }
        }
    }
}
