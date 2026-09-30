package com.oussamaksantini.insightstudio.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.testsupport.CapturingVerificationNotifier;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The whole account flow over real HTTP, with nothing mocked: the client only has what a browser
 * would (cookies set by the API, the CSRF cookie echoed as a header).
 */
class AuthFlowIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "a long enough password";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CapturingVerificationNotifier verificationMail;

    /** Opens the emailed verification link (as the account's owner would, in any browser). */
    private void verifyEmail(String email) throws Exception {
        try (HttpApiClient mailbox = new HttpApiClient(port)) {
            mailbox.get("/api/session");
            expect(mailbox.postJson("/api/auth/verify-email",
                    "{\"token\":\"%s\"}".formatted(verificationMail.sentTo(email).getLast().token())), 204);
        }
    }

    @BeforeEach
    void clean() {
        new SqlFixture(jdbc).clear();
    }

    private static void expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("%s %s -> %s", response.request().method(), response.request().uri(),
                response.body()).isEqualTo(status);
    }

    @Test
    void signUpCreateBusinessImportReadSignOut() throws Exception {
        try (HttpApiClient client = new HttpApiClient(port)) {
            HttpResponse<String> session = client.get("/api/session");
            expect(session, 200);
            assertThat(client.csrfToken()).as("XSRF-TOKEN cookie").isNotBlank();
            assertThat(session.headers().allValues("Set-Cookie")).anySatisfy(c ->
                    assertThat(c).startsWith("XSRF-TOKEN=").containsIgnoringCase("SameSite=Lax").doesNotContainIgnoringCase("HttpOnly"));
            assertThat((Boolean) JsonPath.read(session.body(), "$.authenticated")).isFalse();
            assertThat((Object) JsonPath.read(session.body(), "$.demo")).isNull();
            String anonymousToken = client.csrfToken();

            HttpResponse<String> signUp = client.postJson("/api/auth/sign-up", """
                    {"email": " Owner@Example.com ", "password": "%s", "displayName": "Olive Owner"}
                    """.formatted(PASSWORD));
            expect(signUp, 201);
            assertThat((String) JsonPath.read(signUp.body(), "$.user.email")).isEqualTo("Owner@Example.com");
            assertThat((List<?>) JsonPath.read(signUp.body(), "$.memberships")).isEmpty();
            assertThat(signUp.headers().allValues("Set-Cookie")).anySatisfy(c ->
                    assertThat(c).startsWith(HttpApiClient.SESSION_COOKIE + "=").containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax"));
            assertThat(client.cookie(HttpApiClient.SESSION_COOKIE)).isNotBlank();
            assertThat(client.csrfToken()).as("CSRF token rotated on sign-in").isNotEqualTo(anonymousToken);

            HttpResponse<String> me = client.get("/api/session");
            assertThat((Boolean) JsonPath.read(me.body(), "$.authenticated")).isTrue();
            assertThat((String) JsonPath.read(me.body(), "$.user.displayName")).isEqualTo("Olive Owner");

            // Until the address is verified, no business can be created.
            expect(client.postJson("/api/businesses", "{\"name\":\"X\",\"currency\":\"EUR\",\"timeZone\":\"UTC\"}"), 403);
            verifyEmail("Owner@Example.com");
            HttpResponse<String> created = client.postJson("/api/businesses", """
                    {"name": "Olive's Shop", "currency": "eur", "timeZone": "Europe/Paris"}
                    """);
            expect(created, 201);
            long businessId = ((Number) JsonPath.read(created.body(), "$.businessId")).longValue();
            assertThat((String) JsonPath.read(created.body(), "$.slug")).isEqualTo("olive-s-shop");
            assertThat((String) JsonPath.read(created.body(), "$.role")).isEqualTo("OWNER");
            assertThat((String) JsonPath.read(created.body(), "$.currency")).isEqualTo("EUR");

            // One business: no selector needed. Catalog setup, then an import.
            expect(client.postJson("/api/stores", "{\"code\":\"PAR\",\"name\":\"Paris\",\"city\":\"Paris\"}"), 201);
            expect(client.postJson("/api/products",
                    "{\"sku\":\"TEE-1\",\"name\":\"Tee\",\"category\":\"Tops\",\"listPrice\":25}"), 201);
            String csv = """
                    store_code,receipt_number,sold_at,sku,quantity,unit_price
                    PAR,R-1,2026-06-01T10:00:00Z,TEE-1,2,25.00
                    PAR,R-2,2026-06-02T10:00:00Z,TEE-1,1,20.00
                    """;
            String boundary = "----flow";
            HttpResponse<String> imported = client.postMultipart("/api/imports?dryRun=false", boundary,
                    HttpApiClient.multipartFile(boundary, "sales.csv", csv.getBytes(StandardCharsets.UTF_8)));
            expect(imported, 200);
            assertThat((String) JsonPath.read(imported.body(), "$.status")).isEqualTo("IMPORTED");

            HttpResponse<String> summary = client.get("/api/dashboard/summary?from=2026-06-01&to=2026-06-30",
                    "X-Business-Id", Long.toString(businessId));
            expect(summary, 200);
            assertThat(JsonPath.read(summary.body(), "$.revenue.value").toString()).isIn("70.0", "70", "70.00");
            HttpResponse<String> context = client.get("/api/dashboard/context");
            assertThat((String) JsonPath.read(context.body(), "$.access.role")).isEqualTo("OWNER");
            assertThat((Boolean) JsonPath.read(context.body(), "$.access.canImport")).isTrue();

            // A state-changing request without the CSRF header is refused, even when signed in.
            HttpResponse<String> noCsrf = client.postJsonWithoutCsrf("/api/stores", "{\"code\":\"LYO\",\"name\":\"Lyon\"}");
            expect(noCsrf, 403);
            assertThat(noCsrf.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).contains("application/problem+json"));
            assertThat((String) JsonPath.read(noCsrf.body(), "$.detail")).contains("CSRF");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stores", Long.class)).isEqualTo(1);

            Map<String, String> signedInCookies = client.cookies();
            expect(client.postJson("/api/auth/sign-out", "{}"), 204);
            assertThat(client.cookie(HttpApiClient.SESSION_COOKIE)).isNull();
            HttpResponse<String> after = client.get("/api/dashboard/summary");
            expect(after, 401);
            assertThat((String) JsonPath.read(after.body(), "$.detail")).isEqualTo("Sign in to continue.");

            // The old session cookie is dead too.
            client.setCookies(signedInCookies);
            expect(client.get("/api/businesses"), 401);
        }
    }

    @Test
    void signInChangesTheSessionIdAndReturnsMemberships() throws Exception {
        try (HttpApiClient client = new HttpApiClient(port)) {
            client.get("/api/session");
            expect(client.postJson("/api/auth/sign-up",
                    "{\"email\":\"a@example.com\",\"password\":\"%s\",\"displayName\":\"A\"}".formatted(PASSWORD)), 201);
            verifyEmail("a@example.com");
            expect(client.postJson("/api/businesses", "{\"name\":\"Shop\",\"currency\":\"USD\",\"timeZone\":\"UTC\"}"), 201);
            String first = client.cookie(HttpApiClient.SESSION_COOKIE);
            Map<String, String> firstCookies = client.cookies();

            HttpResponse<String> signIn = client.postJson("/api/auth/sign-in",
                    "{\"email\":\"A@EXAMPLE.COM\",\"password\":\"%s\"}".formatted(PASSWORD));
            expect(signIn, 200);
            assertThat((String) JsonPath.read(signIn.body(), "$.memberships[0].slug")).isEqualTo("shop");
            assertThat((String) JsonPath.read(signIn.body(), "$.memberships[0].role")).isEqualTo("OWNER");
            assertThat(client.cookie(HttpApiClient.SESSION_COOKIE)).isNotBlank().isNotEqualTo(first);
            expect(client.get("/api/businesses"), 200);

            // Session fixation: the pre-sign-in id no longer identifies a session.
            try (HttpApiClient attacker = new HttpApiClient(port)) {
                attacker.setCookies(firstCookies);
                expect(attacker.get("/api/businesses"), 401);
            }
        }
    }

    @Test
    void anonymousCallsAreRefused() throws Exception {
        try (HttpApiClient client = new HttpApiClient(port)) {
            client.get("/api/session");
            HttpResponse<String> response = client.get("/api/dashboard/context");
            expect(response, 401);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).contains("application/problem+json"));
            // With a valid CSRF token, anonymous writes are 401 (not signed in), never processed.
            expect(client.postJson("/api/businesses", "{\"name\":\"X\",\"currency\":\"USD\",\"timeZone\":\"UTC\"}"), 401);
            expect(client.postJson("/api/auth/sign-out", "{}"), 401);
            expect(client.get("/actuator/health"), 200);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM businesses", Long.class)).isZero();
        }
    }
}
