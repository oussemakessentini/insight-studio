package com.oussamaksantini.insightstudio.account;

import com.oussamaksantini.insightstudio.mail.MailOutbox;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class AccountConfiguration {

    /** bcrypt by default; the {@code {id}} prefix lets the hashing scheme evolve later. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Emails verification links through the mail outbox (Mailpit in development). */
    @Bean
    VerificationNotifier verificationNotifier(MailOutbox outbox) {
        return new EmailVerificationNotifier(outbox);
    }

    /** Emails reset links through the mail outbox (Mailpit in development). */
    @Bean
    PasswordResetNotifier passwordResetNotifier(MailOutbox outbox) {
        return new EmailPasswordResetNotifier(outbox);
    }
}
