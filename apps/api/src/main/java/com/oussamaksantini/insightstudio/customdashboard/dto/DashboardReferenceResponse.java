package com.oussamaksantini.insightstudio.customdashboard.dto;

/** A dashboard whose current layout places a given chart ({@code GET /api/charts/{id}/dashboards}). */
public record DashboardReferenceResponse(long id, String name) {
}
