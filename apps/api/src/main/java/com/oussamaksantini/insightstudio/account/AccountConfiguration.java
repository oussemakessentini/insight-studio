package com.oussamaksantini.insightstudio.account;

import com.oussamaksantini.insightstudio.mail.MailDelivery;
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

    /** Emails reset links over SMTP (Mailpit in development). */
    @Bean
    PasswordResetNotifier passwordResetNotifier(MailDelivery mail) {
        return new SmtpPasswordResetNotifier(mail);
    }
}
