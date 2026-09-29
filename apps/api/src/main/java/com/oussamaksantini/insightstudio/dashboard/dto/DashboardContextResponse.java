package com.oussamaksantini.insightstudio.dashboard.dto;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.util.List;

/**
 * Static context the dashboard needs before querying metrics.
 *
 * @param dataRange first and last dates with sales, or {@code null} when there are no sales yet
 */
public record DashboardContextResponse(BusinessInfo business, List<StoreOption> stores, DateRange dataRange) {

    public record BusinessInfo(String name, String slug, String currency, String timeZone) {
    }
}
