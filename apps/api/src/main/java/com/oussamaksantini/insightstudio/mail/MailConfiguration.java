package com.oussamaksantini.insightstudio.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * SMTP delivery for account emails. The server is Spring Boot's {@code spring.mail.*}
 * ({@code MAIL_HOST}, {@code MAIL_PORT}, ... in application.properties; Mailpit on
 * {@code localhost:1025} in development).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailConfiguration.MailSettings.class)
class MailConfiguration {

    /** @param from sender of account emails, e.g. {@code Insight Studio <no-reply@example.com>} */
    @ConfigurationProperties("insight.mail")
    record MailSettings(@DefaultValue("Insight Studio <no-reply@insight-studio.localhost>") String from) {
    }

    /**
     * A few threads and a bounded queue, private to mail: a stuck mail server can neither exhaust
     * memory or threads nor delay other work. Queued emails get 15 seconds to leave on shutdown.
     */
    @Bean(destroyMethod = "shutdown")
    MailDelivery mailDelivery(JavaMailSender sender, MailSettings settings) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("mail-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return new MailDelivery(sender, executor, settings.from());
    }
}
