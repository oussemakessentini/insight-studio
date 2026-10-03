package com.oussamaksantini.insightstudio.chart;

import com.oussamaksantini.insightstudio.chart.dto.ChartCatalogResponse;
import com.oussamaksantini.insightstudio.chart.dto.ChartResponse;
import com.oussamaksantini.insightstudio.chart.dto.ChartResult;
import com.oussamaksantini.insightstudio.chart.dto.ChartRevisionResponse;
import com.oussamaksantini.insightstudio.chart.dto.ChartSummaryResponse;
import com.oussamaksantini.insightstudio.tenancy.CurrentBusiness;
import com.oussamaksantini.insightstudio.tenancy.Role;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.function.Consumer;
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
 * The chart builder's API (docs/chart-builder-contract.md §4, docs/chart-builder-api.md). Members read
 * and run charts; ADMIN and OWNER preview and save them. Not served for the public demo (anonymous
 * callers get a 401 from the security rules).
 *
 * <p>Runs ({@code preview} and {@code data}) hold one of the business's run slots ({@link ChartRunLimiter})
 * for as long as they take; the slot is taken here, after the caller's access is checked and before the
 * service opens its transaction, so a refused run never touches the database.
 */
@RestController
@RequestMapping("/api/charts")
class ChartController {

    /** Which engine computed a result (also on its 503s), as for the reports. */
    static final String ENGINE_HEADER = "X-Report-Engine";

    private final ChartService charts;
    private final CurrentBusiness current;
    private final ChartRunLimiter limiter;

    ChartController(ChartService charts, CurrentBusiness current, ChartRunLimiter limiter) {
        this.charts = charts;
        this.current = current;
        this.limiter = limiter;
    }

    @GetMapping("/catalog")
    ChartCatalogResponse catalog() {
        return charts.catalog();
    }

    @PostMapping("/preview")
    ChartResult preview(@RequestBody(required = false) JsonNode body, HttpServletResponse response) {
        try (ChartRunLimiter.Permit permit = limiter.acquire(current.require(Role.ADMIN).businessId())) {
            return charts.preview(body, engineHeader(response));
        }
    }

    @GetMapping
    List<ChartSummaryResponse> list() {
        return charts.list();
    }

    @PostMapping
    ResponseEntity<ChartResponse> create(@RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(charts.create(body));
    }

    @GetMapping("/{id}")
    ChartResponse get(@PathVariable long id) {
        return charts.get(id);
    }

    @PutMapping("/{id}")
    ChartResponse update(@PathVariable long id, @RequestBody(required = false) JsonNode body) {
        return charts.update(id, body);
    }

    @PostMapping("/{id}/duplicate")
    ResponseEntity<ChartResponse> duplicate(@PathVariable long id, @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(charts.duplicate(id, body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        charts.delete(id);
    }

    @GetMapping("/{id}/revisions")
    List<ChartRevisionResponse> revisions(@PathVariable long id) {
        return charts.revisions(id);
    }

    @GetMapping("/{id}/revisions/{revision}")
    ChartRevisionResponse revision(@PathVariable long id, @PathVariable int revision) {
        return charts.revision(id, revision);
    }

    @GetMapping("/{id}/data")
    ChartResult data(
            @PathVariable long id, @RequestParam(required = false) Integer revision, HttpServletResponse response) {
        try (ChartRunLimiter.Permit permit = limiter.acquire(current.require().businessId())) {
            return charts.data(id, revision, engineHeader(response));
        }
    }

    private static Consumer<String> engineHeader(HttpServletResponse response) {
        return engine -> response.setHeader(ENGINE_HEADER, engine);
    }
}
