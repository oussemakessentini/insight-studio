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
 */
@ConfigurationProperties("insight.charts")
public record ChartProperties(@DefaultValue("PT10S") Duration statementTimeout) {

    public ChartProperties {
        if (statementTimeout.isNegative() || statementTimeout.isZero()) {
            throw new IllegalArgumentException("insight.charts.statement-timeout must be positive.");
        }
    }
}
