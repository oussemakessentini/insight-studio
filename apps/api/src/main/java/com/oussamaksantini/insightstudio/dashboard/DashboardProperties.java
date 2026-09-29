package com.oussamaksantini.insightstudio.dashboard;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param businessSlug business shown on the dashboard; when blank, the first business created is used
 */
@ConfigurationProperties("insight.dashboard")
public record DashboardProperties(String businessSlug) {
}
