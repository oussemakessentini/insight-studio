package com.oussamaksantini.insightstudio.mail;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Queues account emails in {@code mail_outbox} (Flyway V8); {@link MailOutboxWorker} sends them.
 *
 * <p>Call it inside the transaction that stores the token the email carries: the email then exists
 * exactly when the token does (a rollback drops both), and it survives restarts and mail server
 * outages. Nothing about the email's content is logged.
 */
@Component
public class MailOutbox {

    private static final Logger log = LoggerFactory.getLogger(MailOutbox.class);
    private static final int MAX_SUBJECT = 300;

    private final NamedParameterJdbcTemplate jdbc;

    MailOutbox(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param kind what the email is, for logs and the table (e.g. {@code "password reset"})
     * @param sendBefore when the link inside expires; an email still unsent then is dropped (null: never)
     */
    public void enqueue(String kind, String to, String subject, String text, Instant sendBefore) {
        enqueue(kind, to, subject, text, sendBefore, null, null);
    }

    /**
     * Like {@link #enqueue(String, String, String, String, Instant)}, recording which business and/or
     * account the email belongs to: deleting either expires its pending emails and erases their bodies
     * (docs/account-management-contract.md §4).
     */
    public void enqueue(String kind, String to, String subject, String text, Instant sendBefore, Long businessId,
            Long userId) {
        String cleanSubject = subject.length() > MAX_SUBJECT ? subject.substring(0, MAX_SUBJECT) : subject;
        long id = jdbc.queryForObject("""
                INSERT INTO mail_outbox (kind, recipient, subject, body, send_before, business_id, user_id)
                VALUES (:kind, :recipient, :subject, :body, :sendBefore, :businessId, :userId)
                RETURNING id
                """, new MapSqlParameterSource()
                        .addValue("kind", kind)
                        .addValue("recipient", to)
                        .addValue("subject", cleanSubject)
                        .addValue("body", text)
                        .addValue("sendBefore", sendBefore == null ? null : OffsetDateTime.ofInstant(sendBefore, ZoneOffset.UTC))
                        .addValue("businessId", businessId)
                        .addValue("userId", userId),
                Long.class);
        log.debug("Queued a {} email (outbox {}).", kind, id);
    }
}
