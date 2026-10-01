package com.oussamaksantini.insightstudio.analytics;

import com.oussamaksantini.insightstudio.reporting.ReportingContext;
import com.oussamaksantini.insightstudio.tenancy.CurrentBusiness;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Wires the Cube client when a Cube URL is configured. Each setting has a fallback so the API and
 * Cube can share one {@code infra/.env} (which the API imports as plain properties):
 * <ul>
 *   <li>URL: {@code insight.cube.url} (environment variable {@code INSIGHT_CUBE_URL}), else an
 *       {@code INSIGHT_CUBE_URL} entry in {@code infra/.env};</li>
 *   <li>secret: {@code insight.cube.api-secret}, else {@code CUBEJS_API_SECRET} (environment
 *       variable or {@code infra/.env} entry).</li>
 * </ul>
 * A URL without a usable secret fails startup rather than at the first request. The client is
 * also used by the Cube report engine ({@code insight.reports.engine=cube}).
 */
@Configuration(proxyBeanMethods = false)
class CubeConfiguration {

    static final String URL_VARIABLE = "INSIGHT_CUBE_URL";
    static final String SECRET_VARIABLE = "CUBEJS_API_SECRET";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    /** A null bean (absent for {@code ObjectProvider.getIfAvailable()}) when analytics is not configured. */
    @Bean
    CubeClient analyticsCubeClient(CubeProperties properties, Environment environment) {
        return cubeClient(properties, environment);
    }

    @Bean
    AnalyticsService analyticsService(
            CurrentBusiness currentBusiness, ReportingContext reporting, ObjectProvider<CubeClient> cube) {
        return new AnalyticsService(currentBusiness, reporting, cube.getIfAvailable());
    }

    /** {@code null} when analytics is not configured; {@link AnalyticsService} then answers 503. */
    static CubeClient cubeClient(CubeProperties properties, Environment environment) {
        String url = properties.enabled() ? properties.url() : environment.getProperty(URL_VARIABLE, "");
        if (!StringUtils.hasText(url)) {
            return null;
        }
        String secret = StringUtils.hasText(properties.apiSecret())
                ? properties.apiSecret()
                : environment.getProperty(SECRET_VARIABLE, "");
        if (secret.length() < CubeTokens.MIN_SECRET_LENGTH || secret.startsWith("change-me")) {
            throw new IllegalStateException(("A Cube URL is set but %s is missing, a placeholder, or shorter than %d "
                    + "characters; set it to the secret Cube runs with.")
                    .formatted(SECRET_VARIABLE, CubeTokens.MIN_SECRET_LENGTH));
        }
        URI base = URI.create(url.trim().replaceAll("/+$", ""));
        if (!"http".equals(base.getScheme()) && !"https".equals(base.getScheme())) {
            throw new IllegalStateException("The Cube URL must be an http(s) URL.");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new CubeClient(httpClient, base, READ_TIMEOUT, new CubeTokens(secret, Clock.systemUTC()));
    }
}
