package com.oussamaksantini.insightstudio.dashboard.dto;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import com.oussamaksantini.insightstudio.reporting.MetricValue;

public record SummaryResponse(
        DateRange period,
        DateRange previousPeriod,
        Long storeId,
        MetricValue revenue,
        MetricValue orders,
        MetricValue unitsSold,
        MetricValue averageOrderValue) {
}
