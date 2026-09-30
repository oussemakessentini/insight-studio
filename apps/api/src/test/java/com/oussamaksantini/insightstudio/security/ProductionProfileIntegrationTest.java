package com.oussamaksantini.insightstudio.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.testsupport.ApiInstance;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The {@code prod} profile: cookies are Secure (the session cookie a {@code __Host-} cookie), and
 * the API refuses to start with settings that would serve accounts over plain HTTP or without a
 * mail server.
 */
class ProductionProfileIntegrationTest extends PostgresIntegrationTest {

    private static final String EMAIL = "prod@example.com";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcConnectionDetails database;

    @BeforeEach
    void clean() {
        new SqlFixture(jdbc).clear();
        new TestAccounts(jdbc).user(EMAIL);
    }

    /** The external settings a production deployment must provide. */
    private static Map<String, Object> production() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.profiles.active", "prod");
        properties.put("WEB_BASE_URL", "https://app.example.com");
        properties.put("MAIL_HOST", "smtp.example.com");
        properties.put("MAIL_FROM", "Insight Studio <no-reply@example.com>");
        return properties;
    }

    @Test
    void cookiesAreSecureAndTheSessionCookieIsHostPrefixed() throws Exception {
        try (ApiInstance api = ApiInstance.start(database, production()); HttpApiClient client = api.client()) {
            HttpResponse<String> session = client.get("/api/session");
            assertThat(session.headers().allValues("Set-Cookie")).anySatisfy(cookie ->
                    assertThat(cookie).startsWith("XSRF-TOKEN=").containsIgnoringCase("; Secure"));

            HttpResponse<String> signIn = client.postJson("/api/auth/sign-in",
                    "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, TestAccounts.PASSWORD));
            assertThat(signIn.statusCode()).isEqualTo(200);
            List<String> cookies = signIn.headers().allValues("Set-Cookie");
            assertThat(cookies).anySatisfy(cookie -> assertThat(cookie)
                    .startsWith("__Host-SESSION=")
                    .containsIgnoringCase("; Secure")
                    .containsIgnoringCase("; HttpOnly")
                    .containsIgnoringCase("SameSite=Lax")
                    .containsIgnoringCase("Path=/")
                    .doesNotContainIgnoringCase("Domain="));
            assertThat(cookies).allSatisfy(cookie -> assertThat(cookie).containsIgnoringCase("; Secure"));
        }
    }

    @Test
    void refusesToStartWithInsecureCookies() {
        Map<String, Object> properties = production();
        properties.put("COOKIE_SECURE", "false");
        properties.put("insight.security.cookie-secure", "false");
        assertThatThrownBy(() -> ApiInstance.start(database, properties).close())
                .rootCause().hasMessageContaining("insight.security.cookie-secure (COOKIE_SECURE) must be true");
    }

    @Test
    void refusesToStartWithAPlainHttpWebAddress() {
        Map<String, Object> properties = production();
        properties.put("WEB_BASE_URL", "http://app.example.com");
        assertThatThrownBy(() -> ApiInstance.start(database, properties).close())
                .rootCause().hasMessageContaining("must be an https:// address");
    }

    @Test
    void refusesToStartWithoutAMailServer() {
        Map<String, Object> properties = production();
        properties.remove("MAIL_HOST");
        assertThatThrownBy(() -> ApiInstance.start(database, properties).close())
                .rootCause().hasMessageContaining("MAIL_HOST");
    }
}
