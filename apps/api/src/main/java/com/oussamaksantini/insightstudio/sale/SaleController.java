package com.oussamaksantini.insightstudio.sale;

import com.oussamaksantini.insightstudio.sale.SaleService.ListRequest;
import com.oussamaksantini.insightstudio.sale.dto.SaleDetailResponse;
import com.oussamaksantini.insightstudio.sale.dto.SaleListResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only sales register. Date and store parameters behave exactly as on the dashboard. */
@RestController
@RequestMapping("/api/sales")
class SaleController {

    private final SaleService saleService;

    SaleController(SaleService saleService) {
        this.saleService = saleService;
    }

    @GetMapping
    SaleListResponse list(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId,
            @RequestParam(required = false) @Size(max = 40) String q,
            @RequestParam(required = false) @Positive Long productId,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return saleService.list(new ListRequest(from, to, storeId, q, productId, sort, page, size));
    }

    @GetMapping("/{saleId}")
    SaleDetailResponse detail(@PathVariable @Positive long saleId) {
        return saleService.detail(saleId);
    }
}
