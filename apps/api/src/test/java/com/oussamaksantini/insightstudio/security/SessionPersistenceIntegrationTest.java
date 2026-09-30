package com.oussamaksantini.insightstudio.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.testsupport.ApiInstance;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Sessions live in PostgreSQL, not in one server's memory: a session started on one API instance
 * works on another, survives the instance that created it being stopped (a restart), and signing
 * out anywhere ends it everywhere.
 */
class SessionPersistenceIntegrationTest extends PostgresIntegrationTest {

    private static final String EMAIL = "sessions@example.com";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcConnectionDetails database;

    private TestAccounts.TestUser user;

    @BeforeEach
    void clean() {
        new SqlFixture(jdbc).clear();
        user = new TestAccounts(jdbc).user(EMAIL);
    }

    private static void expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("%s %s -> %s", response.request().method(), response.request().uri(),
                response.body()).isEqualTo(status);
    }

    private static Map<String, String> signIn(HttpApiClient client) throws Exception {
        client.get("/api/session");
        expect(client.postJson("/api/auth/sign-in",
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, TestAccounts.PASSWORD)), 200);
        return client.cookies();
    }

    @Test
    void aSessionWorksOnEveryInstanceAndSurvivesARestart() throws Exception {
        Map<String, String> cookies;
        try (ApiInstance first = ApiInstance.start(database); HttpApiClient browser = first.client()) {
            cookies = signIn(browser);
            assertThat(cookies).containsKey(HttpApiClient.SESSION_COOKIE);

            // The same browser on another instance (e.g. the next request behind a load balancer).
            try (HttpApiClient elsewhere = new HttpApiClient(port)) {
                elsewhere.setCookies(cookies);
                expect(elsewhere.get("/api/businesses"), 200);
                HttpResponse<String> session = elsewhere.get("/api/session");
                assertThat((Boolean) JsonPath.read(session.body(), "$.authenticated")).isTrue();
                assertThat((String) JsonPath.read(session.body(), "$.user.email")).isEqualTo(EMAIL);
            }
        }
        // The instance that created the session is gone; a new one starts on the same database.
        try (ApiInstance restarted = ApiInstance.start(database); HttpApiClient browser = restarted.client()) {
            browser.setCookies(cookies);
            expect(browser.get("/api/businesses"), 200);

            // Signing out on this instance ends the session on the others too.
            expect(browser.postJson("/api/auth/sign-out", "{}"), 204);
            try (HttpApiClient elsewhere = new HttpApiClient(port)) {
                elsewhere.setCookies(cookies);
                expect(elsewhere.get("/api/businesses"), 401);
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spring_session", Long.class)).isZero();
    }

    @Test
    void theStoredSessionHoldsOnlyTheAccountAsPlainValues() throws Exception {
        try (HttpApiClient browser = new HttpApiClient(port)) {
            signIn(browser);
        }
        assertThat(jdbc.queryForObject("SELECT principal_name FROM spring_session", String.class))
                .isEqualTo("user:" + user.id());
        List<String> attributes = jdbc.queryForList(
                "SELECT attribute_name FROM spring_session_attributes ORDER BY attribute_name", String.class);
        assertThat(attributes).containsExactly(
                "insight.account.email",
                "insight.account.sessionVersion",
                "insight.account.userId",
                "org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME");
        // No serialized framework objects (a security context would name Spring Security classes).
        List<byte[]> values = jdbc.queryForList("SELECT attribute_bytes FROM spring_session_attributes", byte[].class);
        assertThat(values).noneSatisfy(bytes ->
                assertThat(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)).contains("springframework.security"));
    }

    @Test
    void anonymousVisitorsDoNotCreateSessions() throws Exception {
        try (HttpApiClient browser = new HttpApiClient(port)) {
            browser.get("/api/session");
            expect(browser.get("/api/dashboard/context"), 401);
            expect(browser.postJson("/api/auth/sign-in", "{\"email\":\"%s\",\"password\":\"wrong password!\"}".formatted(EMAIL)), 401);
            assertThat(browser.cookie(HttpApiClient.SESSION_COOKIE)).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spring_session", Long.class)).isZero();
    }

    @Test
    void aPasswordChangeOnOneInstanceSignsOutSessionsOnAnother() throws Exception {
        try (ApiInstance other = ApiInstance.start(database);
                HttpApiClient here = new HttpApiClient(port);
                HttpApiClient there = other.client()) {
            signIn(here);
            signIn(there);
            expect(here.postJson("/api/auth/password/change",
                    "{\"currentPassword\":\"%s\",\"newPassword\":\"a brand new passphrase\"}".formatted(TestAccounts.PASSWORD)), 204);
            expect(here.get("/api/businesses"), 200);
            expect(there.get("/api/businesses"), 401);
        }
    }
}
