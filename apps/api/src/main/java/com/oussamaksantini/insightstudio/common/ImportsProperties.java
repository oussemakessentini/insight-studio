package com.oussamaksantini.insightstudio.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CSV import switch. Imports write data through unauthenticated endpoints, so they are off by
 * default and enabled only by the {@code local} profile ({@code application-local.properties}).
 * Import beans must be conditional on {@value #ENABLED_PROPERTY}{@code =true}, so a deployed
 * configuration does not expose them at all.
 *
 * @param enabled whether import endpoints are registered; {@code false} unless explicitly set
 */
@ConfigurationProperties("insight.imports")
public record ImportsProperties(boolean enabled) {

    public static final String ENABLED_PROPERTY = "insight.imports.enabled";
}
