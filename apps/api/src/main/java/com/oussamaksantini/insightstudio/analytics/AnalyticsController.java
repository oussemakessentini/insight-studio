package com.oussamaksantini.insightstudio.analytics;

import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Analytics served by the private Cube semantic layer, for the current business only. Parameters
 * behave like the dashboard's: inclusive ISO dates in the business's time zone, optional store.
 * 503 when Cube is not configured, 502 when it fails.
 */
@RestController
@RequestMapping("/api/analytics")
class AnalyticsController {

    private final AnalyticsService analytics;

    AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/summary")
    AnalyticsSummaryResponse summary(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId) {
        return analytics.summary(from, to, storeId);
    }
}
