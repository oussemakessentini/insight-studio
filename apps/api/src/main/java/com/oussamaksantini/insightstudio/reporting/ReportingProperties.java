package com.oussamaksantini.insightstudio.reporting;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param businessSlug business shown on the dashboard; when blank, the first business created is used
 */
@ConfigurationProperties("insight.dashboard")
public record ReportingProperties(String businessSlug) {
}
