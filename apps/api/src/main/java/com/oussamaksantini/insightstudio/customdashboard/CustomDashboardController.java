package com.oussamaksantini.insightstudio.customdashboard;

import com.oussamaksantini.insightstudio.customdashboard.dto.DashboardResponse;
import com.oussamaksantini.insightstudio.customdashboard.dto.DashboardRevisionResponse;
import com.oussamaksantini.insightstudio.customdashboard.dto.DashboardSummaryResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Custom dashboards (docs/dashboards-contract.md §4, docs/dashboards-api.md), next to the overview
 * dashboard's {@code /api/dashboard/**}. Members read them; ADMIN and OWNER save them. Not served for
 * the public demo (anonymous callers get a 401 from the security rules).
 */
@RestController
@RequestMapping("/api/dashboards")
class CustomDashboardController {

    private final CustomDashboardService dashboards;

    CustomDashboardController(CustomDashboardService dashboards) {
        this.dashboards = dashboards;
    }

    @GetMapping
    List<DashboardSummaryResponse> list() {
        return dashboards.list();
    }

    @PostMapping
    ResponseEntity<DashboardResponse> create(@RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dashboards.create(body));
    }

    @GetMapping("/{id}")
    DashboardResponse get(@PathVariable long id, @RequestParam(required = false) Integer revision) {
        return dashboards.get(id, revision);
    }

    @PutMapping("/{id}")
    DashboardResponse update(@PathVariable long id, @RequestBody(required = false) JsonNode body) {
        return dashboards.update(id, body);
    }

    @PostMapping("/{id}/duplicate")
    ResponseEntity<DashboardResponse> duplicate(@PathVariable long id, @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dashboards.duplicate(id, body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        dashboards.delete(id);
    }

    @GetMapping("/{id}/revisions")
    List<DashboardRevisionResponse> revisions(@PathVariable long id) {
        return dashboards.revisions(id);
    }
}
