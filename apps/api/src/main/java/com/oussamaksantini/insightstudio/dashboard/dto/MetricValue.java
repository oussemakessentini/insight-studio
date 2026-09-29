package com.oussamaksantini.insightstudio.dashboard.dto;

import java.math.BigDecimal;

/**
 * A metric for the selected period compared with the preceding period of equal length.
 *
 * @param changePercent percentage change, or {@code null} when the previous value is zero
 */
public record MetricValue(BigDecimal value, BigDecimal previousValue, BigDecimal changePercent) {
}
