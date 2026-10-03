package com.oussamaksantini.insightstudio.chart;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits of the chart builder's SQL engine (docs/chart-builder-contract.md §3). The Cube engine uses
 * the report engine's deadline, {@code insight.reports.cube-timeout}.
 *
 * @param statementTimeout PostgreSQL {@code statement_timeout} of a chart query (default 10 s); a
 *     query cancelled by it is a 503
 * @param maxConcurrentRunsPerBusiness chart runs ({@code data} and {@code preview}) one business may have
 *     in progress on this instance (default 6); more are a 429 (docs/dashboards-contract.md §4)
 */
@ConfigurationProperties("insight.charts")
public record ChartProperties(
        @DefaultValue("PT10S") Duration statementTimeout,
        @DefaultValue("6") int maxConcurrentRunsPerBusiness) {

    public ChartProperties {
        if (statementTimeout.isNegative() || statementTimeout.isZero()) {
            throw new IllegalArgumentException("insight.charts.statement-timeout must be positive.");
        }
        if (maxConcurrentRunsPerBusiness < 1) {
            throw new IllegalArgumentException("insight.charts.max-concurrent-runs-per-business must be at least 1.");
        }
    }
}
