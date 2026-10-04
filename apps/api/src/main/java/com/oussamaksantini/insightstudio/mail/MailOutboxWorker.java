package com.oussamaksantini.insightstudio.mail;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends queued emails ({@link MailOutbox}). Any number of API instances may run it:
 *
 * <ul>
 *   <li>A round claims due emails with {@code FOR UPDATE SKIP LOCKED} and a lease
 *       ({@code locked_until}), so two workers never send the same email, and one that crashed
 *       mid-send leaves its emails to be retried when the lease ends.</li>
 *   <li>A failed attempt is retried after the next of {@code retry-delays}; after the last one the
 *       email is marked FAILED. An email whose link has expired ({@code send_before}) is dropped as
 *       EXPIRED instead of sent.</li>
 *   <li>Once an email is sent, failed or expired, its body (with the secret link) is erased.</li>
 *   <li>Logs name the outbox id, kind and SMTP error, never the body or a link.</li>
 * </ul>
 *
 * <p>An email may be sent twice only if an instance dies between the SMTP server accepting it and
 * the row being marked SENT (at-least-once delivery).
 */
@Component
@ConditionalOnProperty(name = "insight.mail.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class MailOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(MailOutboxWorker.class);
    private static final String JOB = "mail_outbox";

    private com.oussamaksantini.insightstudio.ops.JobHeartbeats heartbeats;

    /** Reports each round to the ops metrics (optional: absent in workers built by tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setHeartbeats(com.oussamaksantini.insightstudio.ops.JobHeartbeats heartbeats) {
        this.heartbeats = heartbeats;
    }

    private void beat(boolean ok) {
        if (heartbeats != null) {
            if (ok) {
                heartbeats.succeeded(JOB);
            } else {
                heartbeats.failed(JOB);
            }
        }
    }
    private static final int MAX_ERROR = 500;
    private static final int PURGE_EVERY = 100;

    private final NamedParameterJdbcTemplate jdbc;
    private final SmtpMailer mailer;
    private final MailConfiguration.OutboxSettings settings;
    private final AtomicInteger rounds = new AtomicInteger();

    MailOutboxWorker(NamedParameterJdbcTemplate jdbc, SmtpMailer mailer, MailConfiguration.OutboxSettings settings) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.settings = settings;
    }

    private record Claimed(long id, String kind, String recipient, String subject, String body, int attempts,
            boolean expired) {
    }

    @Scheduled(fixedDelayString = "${insight.mail.outbox.poll-interval:PT2S}", initialDelayString = "PT1S")
    void poll() {
        try {
            deliverDue();
            if (rounds.incrementAndGet() % PURGE_EVERY == 0) {
                purge();
            }
            beat(true);
        } catch (RuntimeException e) {
            // E.g. the database is briefly unavailable: the next round tries again.
            beat(false);
            log.warn("Mail outbox round failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Sends every due email, a batch at a time; returns how many were handled. */
    public int deliverDue() {
        int handled = 0;
        List<Claimed> batch;
        do {
            batch = claim();
            for (Claimed email : batch) {
                deliver(email);
            }
            handled += batch.size();
        } while (batch.size() == settings.batchSize());
        return handled;
    }

    /** Claims due emails for this worker (one statement: claimed rows are committed at once). */
    private List<Claimed> claim() {
        return jdbc.query("""
                UPDATE mail_outbox o
                SET attempts = o.attempts + 1, locked_until = now() + make_interval(secs => :lease)
                FROM (
                    SELECT id FROM mail_outbox
                    WHERE status = 'PENDING' AND next_attempt_at <= now()
                      AND (locked_until IS NULL OR locked_until < now())
                    ORDER BY next_attempt_at, id
                    LIMIT :batch
                    FOR UPDATE SKIP LOCKED
                ) due
                WHERE o.id = due.id
                RETURNING o.id, o.kind, o.recipient, o.subject, o.body, o.attempts,
                          (o.send_before IS NOT NULL AND o.send_before <= now()) AS expired
                """, new MapSqlParameterSource()
                        .addValue("lease", settings.lease().toSeconds())
                        .addValue("batch", settings.batchSize()),
                (rs, i) -> new Claimed(rs.getLong("id"), rs.getString("kind"), rs.getString("recipient"),
                        rs.getString("subject"), rs.getString("body"), rs.getInt("attempts"), rs.getBoolean("expired")));
    }

    private void deliver(Claimed email) {
        if (email.expired()) {
            finish(email.id(), "EXPIRED", null);
            log.info("Dropped a {} email (outbox {}): its link expired before it could be sent.", email.kind(), email.id());
            return;
        }
        try {
            mailer.send(email.recipient(), email.subject(), email.body());
        } catch (Exception e) {
            failed(email, describe(e));
            return;
        }
        finish(email.id(), "SENT", null);
        log.info("Sent a {} email (outbox {}, attempt {}).", email.kind(), email.id(), email.attempts());
    }

    private void failed(Claimed email, String error) {
        if (email.attempts() >= settings.maxAttempts()) {
            finish(email.id(), "FAILED", error);
            log.warn("Gave up on a {} email (outbox {}) after {} attempts: {}", email.kind(), email.id(), email.attempts(), error);
            return;
        }
        Duration wait = settings.retryDelays().get(email.attempts() - 1);
        jdbc.update("""
                UPDATE mail_outbox
                SET locked_until = NULL, last_error = :error,
                    next_attempt_at = now() + make_interval(secs => :wait)
                WHERE id = :id
                """, new MapSqlParameterSource()
                        .addValue("id", email.id())
                        .addValue("error", error)
                        .addValue("wait", wait.toMillis() / 1000.0));
        log.warn("Could not send a {} email (outbox {}, attempt {} of {}); retrying in {}: {}", email.kind(), email.id(),
                email.attempts(), settings.maxAttempts(), wait, error);
    }

    /** Marks the email finished and erases its body. */
    private void finish(long id, String status, String error) {
        jdbc.update("""
                UPDATE mail_outbox
                SET status = :status, body = NULL, locked_until = NULL, finished_at = now(),
                    last_error = COALESCE(:error, last_error)
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("status", status).addValue("error", error));
    }

    /** Deletes finished emails (bodies already erased) past the retention period. */
    public int purge() {
        return jdbc.update("""
                DELETE FROM mail_outbox
                WHERE status <> 'PENDING' AND finished_at < now() - make_interval(secs => :retention)
                """, Map.of("retention", settings.retention().toSeconds()));
    }

    /** The exception's class and message: the SMTP server's answer, never the message body. */
    private static String describe(Exception e) {
        String text = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage().strip());
        text = text.replaceAll("\\s+", " ");
        return text.length() > MAX_ERROR ? text.substring(0, MAX_ERROR) : text;
    }
}
