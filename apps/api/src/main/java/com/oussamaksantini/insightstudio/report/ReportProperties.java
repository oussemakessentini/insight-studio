package com.oussamaksantini.insightstudio.report;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which engine computes the reports' raw totals (docs/cube-reports-contract.md §2). Set with
 * {@code REPORTS_ENGINE} and {@code REPORTS_CUBE_TIMEOUT} (see application.properties).
 *
 * @param engine {@code sql} (default) or {@code cube}; {@code cube} needs a configured Cube
 *     ({@code INSIGHT_CUBE_URL} and its secret), otherwise startup fails. There is no fallback
 *     from one engine to the other at runtime.
 * @param cubeTimeout overall deadline for all Cube calls of one report request (default 10 s)
 */
@ConfigurationProperties("insight.reports")
public record ReportProperties(@DefaultValue("sql") Engine engine, @DefaultValue("PT10S") Duration cubeTimeout) {

    public ReportProperties {
        if (cubeTimeout.isNegative() || cubeTimeout.isZero()) {
            throw new IllegalArgumentException("insight.reports.cube-timeout (REPORTS_CUBE_TIMEOUT) must be positive.");
        }
    }

    public enum Engine {
        SQL,
        CUBE
    }
}
