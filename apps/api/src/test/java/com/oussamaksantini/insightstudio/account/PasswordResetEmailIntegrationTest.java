package com.oussamaksantini.insightstudio.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.testsupport.ApiInstance;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import com.oussamaksantini.insightstudio.testsupport.Mailpit;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Password recovery end to end with real SMTP: an API instance configured like a deployment
 * (its real mail notifier, debug logging) emails the link to Mailpit, the link resets the
 * password, and neither the link nor the token ever reaches the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetEmailIntegrationTest extends PostgresIntegrationTest {

    private static final String EMAIL = "reset-by-mail@example.com";
    private static final Pattern LINK = Pattern.compile("https://app\\.example\\.com/reset-password\\?token=([A-Za-z0-9_-]{43})");

    private static Mailpit mailpit;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcConnectionDetails database;

    @BeforeAll
    static void startMailpit() {
        mailpit = new Mailpit();
    }

    @AfterAll
    static void stopMailpit() {
        mailpit.close();
    }

    @BeforeEach
    void clean() throws Exception {
        new SqlFixture(jdbc).clear();
        new TestAccounts(jdbc).user(EMAIL);
        mailpit.clear();
    }

    static Map<String, Object> deployedLike(Mailpit mailpit) {
        Map<String, Object> properties = new HashMap<>(mailpit.apiProperties());
        properties.put("insight.accounts.web-base-url", "https://app.example.com/");
        properties.put("insight.mail.from", "Insight Studio <no-reply@app.example.com>");
        // Even debug logging must not reveal a link.
        properties.put("logging.level.com.oussamaksantini", "DEBUG");
        properties.put("logging.level.org.springframework.mail", "DEBUG");
        return properties;
    }

    @Test
    void theResetLinkArrivesBySmtpWorksAndIsNeverLogged(CapturedOutput output) throws Exception {
        String token;
        try (ApiInstance api = ApiInstance.start(database, deployedLike(mailpit)); HttpApiClient client = api.client()) {
            client.get("/api/session");
            assertThat(client.postJson("/api/auth/password/forgot", "{\"email\":\"" + EMAIL.toUpperCase() + "\"}").statusCode())
                    .isEqualTo(202);
            assertThat(client.postJson("/api/auth/password/forgot", "{\"email\":\"ghost@example.com\"}").statusCode())
                    .isEqualTo(202);

            List<Mailpit.Message> messages = mailpit.awaitMessages(1);
            Thread.sleep(500);
            // Only the account's owner gets an email; the unknown address gets nothing.
            assertThat(mailpit.messages()).hasSize(1);
            Mailpit.Message message = messages.getFirst();
            assertThat(message.to()).isEqualTo(EMAIL);
            assertThat(message.from()).isEqualTo("no-reply@app.example.com");
            assertThat(message.subject()).isEqualTo("Reset your Insight Studio password");
            assertThat(message.text()).contains("within 30 minutes");
            Matcher link = LINK.matcher(message.text());
            assertThat(link.find()).as("reset link in %s", message.text()).isTrue();
            token = link.group(1);

            assertThat(client.postJson("/api/auth/password/reset",
                    "{\"token\":\"%s\",\"newPassword\":\"a brand new passphrase\"}".formatted(token)).statusCode()).isEqualTo(204);
            assertThat(client.postJson("/api/auth/sign-in",
                    "{\"email\":\"%s\",\"password\":\"a brand new passphrase\"}".formatted(EMAIL)).statusCode()).isEqualTo(200);
        }
        assertThat(output.getAll())
                .contains("Password reset for account")
                .doesNotContain(token)
                .doesNotContain("reset-password?token");
    }

    @Test
    void anUnreachableMailServerDoesNotFailOrSlowTheRequest(CapturedOutput output) throws Exception {
        Map<String, Object> properties = new HashMap<>(deployedLike(mailpit));
        // Nothing listens there.
        properties.put("spring.mail.host", "127.0.0.1");
        properties.put("spring.mail.port", 9);
        try (ApiInstance api = ApiInstance.start(database, properties); HttpApiClient client = api.client()) {
            client.get("/api/session");
            long start = System.nanoTime();
            assertThat(client.postJson("/api/auth/password/forgot", "{\"email\":\"" + EMAIL + "\"}").statusCode()).isEqualTo(202);
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
            long deadline = System.currentTimeMillis() + 15_000;
            while (!output.getAll().contains("Could not send a password reset email") && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
        }
        assertThat(output.getAll()).contains("Could not send a password reset email").doesNotContain("reset-password?token");
    }
}
