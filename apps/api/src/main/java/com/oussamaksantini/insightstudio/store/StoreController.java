package com.oussamaksantini.insightstudio.store;

import com.oussamaksantini.insightstudio.dashboard.dto.RevenueSeriesResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.TopProductsResponse;
import com.oussamaksantini.insightstudio.store.dto.StoreDetailResponse;
import com.oussamaksantini.insightstudio.store.dto.StoreListResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only per-store performance. Date parameters behave exactly as on the dashboard endpoints;
 * the store comes from the path, so there is no {@code storeId} parameter.
 */
@RestController
@RequestMapping("/api/stores")
class StoreController {

    private final StoreService storeService;

    StoreController(StoreService storeService) {
        this.storeService = storeService;
    }

    @GetMapping
    StoreListResponse list(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return storeService.list(from, to);
    }

    @GetMapping("/{storeId}")
    StoreDetailResponse detail(
            @PathVariable @Positive long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return storeService.detail(storeId, from, to);
    }

    @GetMapping("/{storeId}/revenue")
    RevenueSeriesResponse revenue(
            @PathVariable @Positive long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) String granularity) {
        return storeService.revenue(storeId, from, to, granularity);
    }

    @GetMapping("/{storeId}/top-products")
    TopProductsResponse topProducts(
            @PathVariable @Positive long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int limit) {
        return storeService.topProducts(storeId, from, to, limit);
    }
}
