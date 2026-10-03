package com.oussamaksantini.insightstudio.customdashboard.dto;

/**
 * A widget with the live summary of its chart.
 *
 * @param missing whether the chart was deleted ({@code chart} is then {@code null})
 */
public record DashboardWidgetResponse(String id, long chartId, boolean missing, Chart chart) {

    /** The chart's current title, visualization and revision. */
    public record Chart(long id, String title, String visualization, int revision) {
    }
}
