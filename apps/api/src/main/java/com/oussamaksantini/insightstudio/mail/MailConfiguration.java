package com.oussamaksantini.insightstudio.mail;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Account emails: written to the {@link MailOutbox} and sent by the {@link MailOutboxWorker} over
 * SMTP ({@code spring.mail.*}: {@code MAIL_HOST}, {@code MAIL_PORT}, ... in application.properties;
 * Mailpit on {@code localhost:1025} in development).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({MailConfiguration.MailSettings.class, MailConfiguration.OutboxSettings.class})
class MailConfiguration {

    /** @param from sender of account emails, e.g. {@code Insight Studio <no-reply@example.com>} */
    @ConfigurationProperties("insight.mail")
    record MailSettings(@DefaultValue("Insight Studio <no-reply@insight-studio.localhost>") String from) {
    }

    /**
     * {@code insight.mail.outbox.*}.
     *
     * @param enabled whether this instance runs the worker (every instance may; they share the work)
     * @param pollInterval how often the worker looks for due emails
     * @param retryDelays waits after the 1st, 2nd, ... failed attempt; after the last one the email
     *     is abandoned, so an email gets {@code retryDelays.size() + 1} attempts at most
     * @param batchSize emails claimed per round
     * @param lease how long a claimed email belongs to one worker (a crashed worker's emails are
     *     retried after it)
     * @param retention how long finished rows (bodies already erased) are kept
     */
    @ConfigurationProperties("insight.mail.outbox")
    record OutboxSettings(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT2S") Duration pollInterval,
            @DefaultValue({"PT30S", "PT2M", "PT10M", "PT30M", "PT2H"}) List<Duration> retryDelays,
            @DefaultValue("20") int batchSize,
            @DefaultValue("PT2M") Duration lease,
            @DefaultValue("P7D") Duration retention) {

        int maxAttempts() {
            return retryDelays.size() + 1;
        }
    }
}
