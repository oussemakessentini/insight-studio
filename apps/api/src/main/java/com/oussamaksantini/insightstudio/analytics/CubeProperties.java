package com.oussamaksantini.insightstudio.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Connection to the private Cube semantic layer (docs/analytics.md).
 *
 * <p>Set with environment variables: {@code INSIGHT_CUBE_URL} (e.g. {@code http://localhost:4000})
 * and {@code CUBEJS_API_SECRET}, the secret Cube verifies tokens with. {@code insight.cube.api-secret}
 * ({@code INSIGHT_CUBE_API_SECRET}) takes precedence over {@code CUBEJS_API_SECRET} when set; see
 * {@link CubeConfiguration}.
 *
 * @param url base URL of the Cube API; blank (the default) disables analytics
 * @param apiSecret HS256 secret shared with Cube; blank means "use {@code CUBEJS_API_SECRET}"
 */
@ConfigurationProperties("insight.cube")
public record CubeProperties(String url, String apiSecret) {

    /** Whether a Cube URL is configured. */
    public boolean enabled() {
        return StringUtils.hasText(url);
    }
}
