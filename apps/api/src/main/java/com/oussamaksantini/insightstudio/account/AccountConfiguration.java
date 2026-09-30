package com.oussamaksantini.insightstudio.account;

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

    /** Logs reset links (development only). A {@code @Primary} bean elsewhere replaces it. */
    @Bean
    PasswordResetNotifier passwordResetNotifier() {
        return new LoggingPasswordResetNotifier();
    }
}
