package com.oussamaksantini.insightstudio.customdashboard;

import com.oussamaksantini.insightstudio.customdashboard.dto.DashboardReferenceResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/charts/{id}/dashboards}: the dashboards that use a chart, so the UI can warn before
 * the chart is deleted (docs/dashboards-contract.md §3). Members only, like the other chart reads.
 */
@RestController
class ChartDashboardsController {

    private final CustomDashboardService dashboards;

    ChartDashboardsController(CustomDashboardService dashboards) {
        this.dashboards = dashboards;
    }

    @GetMapping("/api/charts/{id}/dashboards")
    List<DashboardReferenceResponse> dashboards(@PathVariable long id) {
        return dashboards.dashboardsUsingChart(id);
    }
}
