package com.oussamaksantini.insightstudio.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test beans shared by every integration test context. */
@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfiguration {

    @Bean
    @Primary
    CapturingPasswordResetNotifier capturingPasswordResetNotifier() {
        return new CapturingPasswordResetNotifier();
    }
}
