package com.oussamaksantini.insightstudio.dashboard.dto;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.util.List;

/**
 * Static context the dashboard needs before querying metrics.
 *
 * @param dataRange first and last dates with sales, or {@code null} when there are no sales yet
 * @param features optional features enabled on this API, so the UI can hide what isn't available
 */
public record DashboardContextResponse(
        BusinessInfo business, List<StoreOption> stores, DateRange dataRange, Features features) {

    public record BusinessInfo(String name, String slug, String currency, String timeZone) {
    }

    /** @param importsEnabled whether the CSV import endpoints exist (local development only) */
    public record Features(boolean importsEnabled) {
    }
}
