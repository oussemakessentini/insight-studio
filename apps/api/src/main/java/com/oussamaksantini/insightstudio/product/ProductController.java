package com.oussamaksantini.insightstudio.product;

import com.oussamaksantini.insightstudio.product.ProductService.ListRequest;
import com.oussamaksantini.insightstudio.product.dto.CreateProductRequest;
import com.oussamaksantini.insightstudio.product.dto.ProductCategoriesResponse;
import com.oussamaksantini.insightstudio.product.dto.ProductDetailResponse;
import com.oussamaksantini.insightstudio.product.dto.ProductInfo;
import com.oussamaksantini.insightstudio.product.dto.ProductListResponse;
import com.oussamaksantini.insightstudio.product.dto.ProductSalesTrendResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Product catalogue with sales performance. Date and store parameters behave exactly as on the
 * dashboard endpoints. {@code POST} adds a product to the current business (ADMIN or OWNER).
 */
@RestController
@RequestMapping("/api/products")
class ProductController {

    private final ProductService productService;

    ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    ProductListResponse list(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Size(max = 100) String category,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return productService.list(new ListRequest(from, to, storeId, q, category, sort, direction, page, size));
    }

    @PostMapping
    ResponseEntity<ProductInfo> create(@RequestBody CreateProductRequest body) {
        ProductInfo product = productService.create(body.sku(), body.name(), body.category(), body.listPrice());
        return ResponseEntity.status(HttpStatus.CREATED).body(product);
    }

    @GetMapping("/categories")
    ProductCategoriesResponse categories() {
        return productService.categories();
    }

    @GetMapping("/{productId}")
    ProductDetailResponse detail(
            @PathVariable @Positive long productId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId) {
        return productService.detail(productId, from, to, storeId);
    }

    @GetMapping("/{productId}/sales-trend")
    ProductSalesTrendResponse salesTrend(
            @PathVariable @Positive long productId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long storeId,
            @RequestParam(required = false) String granularity) {
        return productService.salesTrend(productId, from, to, storeId, granularity);
    }
}
