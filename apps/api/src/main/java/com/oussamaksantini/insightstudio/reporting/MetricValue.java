package com.oussamaksantini.insightstudio.reporting;

import java.math.BigDecimal;

/**
 * A metric for the selected period compared with the preceding period of equal length.
 *
 * @param changePercent percentage change, or {@code null} when the previous value is zero
 */
public record MetricValue(BigDecimal value, BigDecimal previousValue, BigDecimal changePercent) {

    public static MetricValue of(BigDecimal value, BigDecimal previousValue) {
        return new MetricValue(value, previousValue, ReportCalculations.percentChange(value, previousValue));
    }

    public static MetricValue of(long value, long previousValue) {
        return of(BigDecimal.valueOf(value), BigDecimal.valueOf(previousValue));
    }
}
