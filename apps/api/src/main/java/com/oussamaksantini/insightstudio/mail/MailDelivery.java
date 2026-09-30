package com.oussamaksantini.insightstudio.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sends plain-text account emails (password resets, invitations) over SMTP.
 *
 * <ul>
 *   <li>Asynchronous: the request that triggers an email answers without waiting for the mail
 *       server, so response times do not reveal whether an email was sent (e.g. whether an
 *       account exists), and a slow or unreachable server does not slow the API down.</li>
 *   <li>After commit: inside a transaction, the email leaves only once the token it carries is
 *       stored.</li>
 *   <li>Never logged: emails carry secret links, so neither the body nor the link is ever
 *       written to a log. Failures log the kind of email and the error, not the content.</li>
 * </ul>
 */
public class MailDelivery {

    private static final Logger log = LoggerFactory.getLogger(MailDelivery.class);

    private final JavaMailSender sender;
    private final Executor executor;
    private final String from;

    public MailDelivery(JavaMailSender sender, Executor executor, String from) {
        this.sender = sender;
        this.executor = executor;
        this.from = from;
    }

    /**
     * Queues an email to {@code to}.
     *
     * @param kind what the email is, for logs (e.g. {@code "password reset"})
     */
    public void send(String kind, String to, String subject, String text) {
        Runnable delivery = () -> deliver(kind, to, subject, text);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue(kind, delivery);
                }
            });
        } else {
            enqueue(kind, delivery);
        }
    }

    /** Lets queued emails leave (when the executor supports it), then stops. */
    public void shutdown() {
        if (executor instanceof ThreadPoolTaskExecutor pool) {
            pool.shutdown();
        }
    }

    private void enqueue(String kind, Runnable delivery) {
        try {
            executor.execute(delivery);
        } catch (RejectedExecutionException e) {
            log.warn("Mail queue is full; a {} email was dropped.", kind);
        }
    }

    private void deliver(String kind, String to, String subject, String text) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, false);
            sender.send(message);
            log.debug("Sent a {} email.", kind);
        } catch (MailException | MessagingException e) {
            // The exception names the server's answer, never the message body.
            log.warn("Could not send a {} email: {}", kind, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Could not send a {} email: {}", kind, e.getClass().getSimpleName());
        }
    }
}
