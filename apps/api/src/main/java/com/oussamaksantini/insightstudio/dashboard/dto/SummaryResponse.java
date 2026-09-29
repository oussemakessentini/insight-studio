package com.oussamaksantini.insightstudio.dashboard.dto;

public record SummaryResponse(
        DateRange period,
        DateRange previousPeriod,
        Long storeId,
        MetricValue revenue,
        MetricValue orders,
        MetricValue unitsSold,
        MetricValue averageOrderValue) {
}
