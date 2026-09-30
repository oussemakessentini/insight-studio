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
 * Account emails end to end with real SMTP: an API instance configured like a deployment (its real
 * notifiers, debug logging) emails password-reset and invitation links to Mailpit, the links work,
 * and neither a link nor a token ever reaches the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class AccountEmailIntegrationTest extends PostgresIntegrationTest {

    private static final String EMAIL = "reset-by-mail@example.com";
    private static final Pattern INVITE_LINK = Pattern.compile("https://app\\.example\\.com/invite\\?token=([A-Za-z0-9_-]{43})");
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
        properties.put("insight.mail.outbox.enabled", true);
        properties.put("insight.mail.outbox.poll-interval", "PT0.2S");
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
    void anInvitationArrivesBySmtpWorksAndIsNeverLogged(CapturedOutput output) throws Exception {
        long business = new SqlFixture(jdbc).business("Mail Co", "mail-co", "EUR", "UTC");
        jdbc.update("INSERT INTO memberships (user_id, business_id, role) SELECT id, ?, 'OWNER' FROM users WHERE email = ?",
                business, EMAIL);
        String token;
        try (ApiInstance api = ApiInstance.start(database, deployedLike(mailpit));
                HttpApiClient owner = api.client();
                HttpApiClient invitee = api.client()) {
            owner.get("/api/session");
            assertThat(owner.postJson("/api/auth/sign-in",
                    "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, TestAccounts.PASSWORD)).statusCode()).isEqualTo(200);
            assertThat(owner.postJson("/api/businesses/%d/invitations".formatted(business),
                    "{\"email\":\"new.person@example.com\",\"role\":\"ADMIN\"}").statusCode()).isEqualTo(201);

            Mailpit.Message message = mailpit.awaitMessages(1).getFirst();
            assertThat(message.to()).isEqualTo("new.person@example.com");
            assertThat(message.subject()).isEqualTo("You're invited to join Mail Co on Insight Studio");
            assertThat(message.text()).contains("invited you to join Mail Co on Insight Studio as an admin")
                    .contains("(new.person@example.com)").contains("expires on");
            Matcher link = INVITE_LINK.matcher(message.text());
            assertThat(link.find()).as("invitation link in %s", message.text()).isTrue();
            token = link.group(1);

            invitee.get("/api/session");
            assertThat(invitee.postJson("/api/auth/sign-up",
                    "{\"email\":\"new.person@example.com\",\"password\":\"%s\",\"displayName\":\"New\"}"
                            .formatted(TestAccounts.PASSWORD)).statusCode()).isEqualTo(202);
            assertThat(invitee.postJson("/api/auth/sign-in", "{\"email\":\"new.person@example.com\",\"password\":\"%s\"}"
                    .formatted(TestAccounts.PASSWORD)).statusCode()).isEqualTo(200);
            assertThat(invitee.postJson("/api/invitations/accept", "{\"token\":\"%s\"}".formatted(token)).statusCode())
                    .isEqualTo(200);
        }
        assertThat(jdbc.queryForObject(
                "SELECT m.role FROM memberships m JOIN users u ON u.id = m.user_id "
                        + "WHERE u.email = 'new.person@example.com' AND m.business_id = ?", String.class, business))
                .isEqualTo("ADMIN");
        assertThat(output.getAll())
                .contains("by invitation")
                .doesNotContain(token)
                .doesNotContain("invite?token")
                .doesNotContain("new.person@example.com");
    }

    @Test
    void signUpEmailsArriveBySmtpAndNeverRevealWhetherAnAccountExisted(CapturedOutput output) throws Exception {
        Pattern verifyLink = Pattern.compile("https://app\\.example\\.com/verify-email\\?token=([A-Za-z0-9_-]{43})");
        String token;
        try (ApiInstance api = ApiInstance.start(database, deployedLike(mailpit)); HttpApiClient client = api.client()) {
            client.get("/api/session");
            int fresh = client.postJson("/api/auth/sign-up",
                    "{\"email\":\"fresh@example.com\",\"password\":\"%s\",\"displayName\":\"Fresh\"}"
                            .formatted(TestAccounts.PASSWORD)).statusCode();
            int existing = client.postJson("/api/auth/sign-up",
                    "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Other\"}"
                            .formatted(EMAIL, TestAccounts.PASSWORD)).statusCode();
            assertThat(fresh).isEqualTo(202).isEqualTo(existing);

            List<Mailpit.Message> messages = mailpit.awaitMessages(2);
            Mailpit.Message verify = messages.stream().filter(m -> m.to().equals("fresh@example.com")).findFirst().orElseThrow();
            Mailpit.Message notice = messages.stream().filter(m -> m.to().equals(EMAIL)).findFirst().orElseThrow();
            assertThat(verify.subject()).isEqualTo("Verify your email for Insight Studio");
            Matcher link = verifyLink.matcher(verify.text());
            assertThat(link.find()).as("verification link in %s", verify.text()).isTrue();
            token = link.group(1);
            assertThat(notice.subject()).isEqualTo("You already have an Insight Studio account");
            assertThat(notice.text()).contains("https://app.example.com/sign-in").contains("https://app.example.com/forgot-password")
                    .doesNotContain("token=");

            assertThat(client.postJson("/api/auth/verify-email", "{\"token\":\"%s\"}".formatted(token)).statusCode())
                    .isEqualTo(204);
        }
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE email = 'fresh@example.com'",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mail_outbox WHERE body IS NOT NULL", Long.class)).isZero();
        assertThat(output.getAll()).doesNotContain(token).doesNotContain("verify-email?token");
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
        // The email waits in the outbox for the next attempt.
        assertThat(jdbc.queryForObject("SELECT status FROM mail_outbox", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT last_error FROM mail_outbox", String.class)).isNotBlank();
    }
}
