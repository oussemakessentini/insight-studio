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
 *     in progress across all API instances (default 6); more are a 429 (docs/dashboards-contract.md §4)
 * @param runSlotTtl how long a run's slot counts when its instance never releases it (crash); longer than
 *     any chart run may take (statement timeout, Cube deadline), default 2 minutes
 */
@ConfigurationProperties("insight.charts")
public record ChartProperties(
        @DefaultValue("PT10S") Duration statementTimeout,
        @DefaultValue("6") int maxConcurrentRunsPerBusiness,
        @DefaultValue("PT2M") Duration runSlotTtl) {

    public ChartProperties {
        if (statementTimeout.isNegative() || statementTimeout.isZero()) {
            throw new IllegalArgumentException("insight.charts.statement-timeout must be positive.");
        }
        if (runSlotTtl.compareTo(statementTimeout) <= 0) {
            throw new IllegalArgumentException("insight.charts.run-slot-ttl must be longer than insight.charts.statement-timeout.");
        }
        if (maxConcurrentRunsPerBusiness < 1) {
            throw new IllegalArgumentException("insight.charts.max-concurrent-runs-per-business must be at least 1.");
        }
    }
}
