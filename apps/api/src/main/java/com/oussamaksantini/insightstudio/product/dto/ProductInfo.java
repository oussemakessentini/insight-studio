package com.oussamaksantini.insightstudio.product.dto;

import java.math.BigDecimal;

/** Catalogue data for a product; {@code listPrice} is the current price, not what past sales were charged. */
public record ProductInfo(long id, String sku, String name, String category, BigDecimal listPrice) {
}
