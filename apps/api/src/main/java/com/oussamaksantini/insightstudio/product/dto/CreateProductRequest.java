package com.oussamaksantini.insightstudio.product.dto;

import java.math.BigDecimal;

/** {@code POST /api/products}: {@code listPrice} is the current catalogue price (0 or more, 2 decimals). */
public record CreateProductRequest(String sku, String name, String category, BigDecimal listPrice) {
}
