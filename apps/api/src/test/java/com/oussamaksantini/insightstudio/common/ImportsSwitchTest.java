package com.oussamaksantini.insightstudio.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Imports expose unauthenticated write endpoints, so they must stay off unless the local
 * development profile is explicitly active.
 */
class ImportsSwitchTest {

    @Test
    void disabledInBaseAndDemoConfiguration() throws IOException {
        assertThat(load("application.properties").getProperty(ImportsProperties.ENABLED_PROPERTY)).isEqualTo("false");
        assertThat(load("application-demo.properties").getProperty(ImportsProperties.ENABLED_PROPERTY)).isNull();
    }

    @Test
    void enabledOnlyByLocalProfile() throws IOException {
        assertThat(load("application-local.properties").getProperty(ImportsProperties.ENABLED_PROPERTY)).isEqualTo("true");
    }

    private static Properties load(String name) throws IOException {
        return PropertiesLoaderUtils.loadProperties(new ClassPathResource(name));
    }
}
