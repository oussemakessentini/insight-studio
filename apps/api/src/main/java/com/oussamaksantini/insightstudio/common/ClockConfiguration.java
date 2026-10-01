package com.oussamaksantini.insightstudio.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's notion of "now" (relative saved-report ranges, export timestamps). Always UTC;
 * callers convert to the business's time zone. Tests may provide a fixed clock instead.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
