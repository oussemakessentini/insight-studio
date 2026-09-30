package com.oussamaksantini.insightstudio.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.testsupport.ApiInstance;
import com.oussamaksantini.insightstudio.testsupport.Mailpit;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
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
 * The mail outbox with real SMTP (Mailpit) and real API instances: queued emails survive a
 * restart, failed deliveries are retried a bounded number of times, expired links are not sent,
 * several workers never send an email twice, and bodies are erased and never logged.
 */
@ExtendWith(OutputCaptureExtension.class)
class MailOutboxIntegrationTest extends PostgresIntegrationTest {

    private static final String SECRET = "https://app.example.com/reset-password?token=SECRET-TOKEN-123";

    private static Mailpit mailpit;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcConnectionDetails database;

    /** Queues emails through the real component, as the notifiers do (the worker is off here). */
    @Autowired
    MailOutbox outbox;

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
        mailpit.clear();
    }

    private Map<String, Object> worker(Map<String, Object> extra) {
        Map<String, Object> properties = new HashMap<>(mailpit.apiProperties());
        properties.put("insight.mail.outbox.enabled", true);
        properties.put("insight.mail.outbox.poll-interval", "PT0.2S");
        properties.put("insight.mail.from", "Insight Studio <no-reply@app.example.com>");
        properties.put("logging.level.com.oussamaksantini", "DEBUG");
        properties.putAll(extra);
        return properties;
    }

    /** A worker whose SMTP server refuses connections. */
    private Map<String, Object> brokenSmtp(String retryDelays) {
        return worker(Map.of("spring.mail.host", "127.0.0.1", "spring.mail.port", 9,
                "insight.mail.outbox.retry-delays", retryDelays));
    }

    private void queue(String to) {
        outbox.enqueue("password reset", to, "Reset your Insight Studio password", "Open " + SECRET, null);
    }

    private String status(String to) {
        return jdbc.queryForObject("SELECT status FROM mail_outbox WHERE recipient = ?", String.class, to);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("condition not met in time").isBefore(deadline);
            Thread.sleep(100);
        }
    }

    @Test
    void emailsQueuedWhileMailIsDownAreSentAfterARestart(CapturedOutput output) throws Exception {
        // First instance: the mail server is unreachable, and the retry is an hour away.
        try (ApiInstance down = ApiInstance.start(database, brokenSmtp("PT1H"))) {
            queue("a@example.com");
            queue("b@example.com");
            await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM mail_outbox WHERE attempts = 1 AND last_error IS NOT NULL",
                    Long.class) == 2);
        }
        assertThat(status("a@example.com")).isEqualTo("PENDING");
        assertThat(mailpit.messages()).isEmpty();

        // The instance is gone. After a restart with a working mail server, the due emails go out.
        jdbc.update("UPDATE mail_outbox SET next_attempt_at = now()");
        try (ApiInstance restarted = ApiInstance.start(database, worker(Map.of()))) {
            await(() -> "SENT".equals(status("a@example.com")) && "SENT".equals(status("b@example.com")));
        }
        List<Mailpit.Message> messages = mailpit.awaitMessages(2);
        assertThat(messages).extracting(Mailpit.Message::to).containsExactlyInAnyOrder("a@example.com", "b@example.com");
        assertThat(messages.getFirst().text()).contains(SECRET);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mail_outbox WHERE body IS NOT NULL", Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT attempts FROM mail_outbox", Integer.class)).containsOnly(2);
        assertThat(output.getAll()).doesNotContain("SECRET-TOKEN-123");
    }

    @Test
    void anEmailClaimedByAWorkerThatDiedIsRetriedWhenItsLeaseEnds() throws Exception {
        queue("orphan@example.com");
        // A worker claimed it (attempt 1) and died before finishing.
        jdbc.update("UPDATE mail_outbox SET attempts = 1, locked_until = now() + interval '1 hour'");
        try (ApiInstance api = ApiInstance.start(database, worker(Map.of()))) {
            Thread.sleep(1_000);
            assertThat(status("orphan@example.com")).as("still leased").isEqualTo("PENDING");
            jdbc.update("UPDATE mail_outbox SET locked_until = now() - interval '1 second'");
            await(() -> "SENT".equals(status("orphan@example.com")));
        }
        assertThat(mailpit.awaitMessages(1)).hasSize(1);
    }

    @Test
    void failedDeliveriesAreRetriedABoundedNumberOfTimesThenAbandoned(CapturedOutput output) throws Exception {
        try (ApiInstance api = ApiInstance.start(database, brokenSmtp("PT0.3S,PT0.3S"))) {
            queue("never@example.com");
            await(() -> "FAILED".equals(status("never@example.com")));
            Thread.sleep(1_000);
        }
        Map<String, Object> row = jdbc.queryForMap("SELECT attempts, body, last_error, finished_at FROM mail_outbox");
        assertThat(row.get("attempts")).as("1 attempt + 2 retries, then no more").isEqualTo(3);
        assertThat(row.get("body")).as("body erased").isNull();
        assertThat((String) row.get("last_error")).contains("Mail");
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(output.getAll())
                .contains("Could not send a password reset email")
                .contains("Gave up on a password reset email")
                .doesNotContain("SECRET-TOKEN-123")
                .doesNotContain("never@example.com");
    }

    @Test
    void anEmailWhoseLinkExpiredIsDroppedNotSent() throws Exception {
        outbox.enqueue("password reset", "late@example.com", "Reset", "Open " + SECRET, Instant.now().minus(Duration.ofMinutes(1)));
        try (ApiInstance api = ApiInstance.start(database, worker(Map.of()))) {
            await(() -> "EXPIRED".equals(status("late@example.com")));
        }
        assertThat(jdbc.queryForObject("SELECT body FROM mail_outbox", String.class)).isNull();
        Thread.sleep(500);
        assertThat(mailpit.messages()).isEmpty();
    }

    @Test
    void twoWorkersNeverSendTheSameEmailTwice() throws Exception {
        int count = 40;
        for (int i = 0; i < count; i++) {
            queue("person" + i + "@example.com");
        }
        Map<String, Object> small = worker(Map.of("insight.mail.outbox.batch-size", 3));
        try (ApiInstance first = ApiInstance.start(database, small); ApiInstance second = ApiInstance.start(database, small)) {
            await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM mail_outbox WHERE status = 'SENT'", Long.class) == count);
        }
        Thread.sleep(500);
        List<Mailpit.Message> messages = mailpit.awaitMessages(count);
        assertThat(messages).hasSize(count);
        assertThat(messages).extracting(Mailpit.Message::to).doesNotHaveDuplicates();
        assertThat(jdbc.queryForList("SELECT attempts FROM mail_outbox", Integer.class)).containsOnly(1);
    }

    @Test
    void aRolledBackTransactionQueuesNothing() {
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        tx.executeWithoutResult(status -> {
            queue("rolled-back@example.com");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mail_outbox", Long.class)).isZero();
    }

    @Test
    void finishedEmailsArePurgedAfterTheRetentionPeriod() throws Exception {
        queue("old@example.com");
        queue("new@example.com");
        jdbc.update("UPDATE mail_outbox SET status = 'SENT', body = NULL, finished_at = now() - interval '8 days' WHERE recipient = 'old@example.com'");
        try (ApiInstance api = ApiInstance.start(database, worker(Map.of()))) {
            await(() -> "SENT".equals(status("new@example.com")));
            MailOutboxWorker worker = api.bean(MailOutboxWorker.class);
            assertThat(worker.purge()).isEqualTo(1);
        }
        assertThat(jdbc.queryForList("SELECT recipient FROM mail_outbox", String.class)).containsExactly("new@example.com");
    }
}
