package com.oussamaksantini.insightstudio.dashboard;

import com.oussamaksantini.insightstudio.dashboard.dto.DashboardContextResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.RecentSalesResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.RevenueSeriesResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.StoreSalesResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.SummaryResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.TopProductsResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only dashboard endpoints. Date parameters are inclusive ISO dates ({@code yyyy-MM-dd})
 * interpreted in the business's time zone.
 */
@RestController
@RequestMapping("/api/dashboard")
class DashboardController {

    private final DashboardService dashboard;

    DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/context")
    DashboardContextResponse context() {
        return dashboard.context();
    }

    @GetMapping("/summary")
    SummaryResponse summary(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId) {
        return dashboard.summary(from, to, storeId);
    }

    @GetMapping("/revenue")
    RevenueSeriesResponse revenue(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId,
            @RequestParam(required = false) String granularity) {
        return dashboard.revenue(from, to, storeId, granularity);
    }

    @GetMapping("/sales-by-store")
    StoreSalesResponse salesByStore(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId) {
        return dashboard.salesByStore(from, to, storeId);
    }

    @GetMapping("/top-products")
    TopProductsResponse topProducts(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId,
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int limit) {
        return dashboard.topProducts(from, to, storeId, limit);
    }

    @GetMapping("/recent-sales")
    RecentSalesResponse recentSales(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return dashboard.recentSales(from, to, storeId, limit);
    }
}
