package com.oussamaksantini.insightstudio.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CubeConfigurationTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void disabledWithoutAUrl() {
        assertThat(CubeConfiguration.cubeClient(new CubeProperties(null, null), new MockEnvironment())).isNull();
        assertThat(CubeConfiguration.cubeClient(new CubeProperties(" ", SECRET), new MockEnvironment())).isNull();
    }

    @Test
    void usesCubejsApiSecretWhenNoApiSecretPropertyIsSet() {
        MockEnvironment env = new MockEnvironment().withProperty("CUBEJS_API_SECRET", SECRET);

        assertThat(CubeConfiguration.cubeClient(new CubeProperties("http://localhost:4000", null), env)).isNotNull();
    }

    @Test
    void readsTheUrlFromAnInfraEnvEntry() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("INSIGHT_CUBE_URL", "http://localhost:4000")
                .withProperty("CUBEJS_API_SECRET", SECRET);

        assertThat(CubeConfiguration.cubeClient(new CubeProperties(null, null), env)).isNotNull();
    }

    @Test
    void failsStartupWithAUrlButNoUsableSecret() {
        CubeProperties url = new CubeProperties("http://localhost:4000", null);

        assertThatThrownBy(() -> CubeConfiguration.cubeClient(url, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CUBEJS_API_SECRET");
        assertThatThrownBy(() -> CubeConfiguration.cubeClient(url,
                new MockEnvironment().withProperty("CUBEJS_API_SECRET", "too-short")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CubeConfiguration.cubeClient(url,
                new MockEnvironment().withProperty("CUBEJS_API_SECRET", "change-me-to-a-long-random-secret")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonHttpUrls() {
        assertThatThrownBy(() -> CubeConfiguration.cubeClient(new CubeProperties("file:///etc/passwd", SECRET),
                new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
    }
}
