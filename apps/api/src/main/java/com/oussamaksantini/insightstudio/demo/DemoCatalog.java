package com.oussamaksantini.insightstudio.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;

/**
 * Fixed definition of the fictional "Fieldstone Apparel Co." demo business: its stores, catalogue,
 * price history and seasonal demand. Everything here is invented.
 */
final class DemoCatalog {

    static final String BUSINESS_NAME = "Fieldstone Apparel Co.";
    static final String BUSINESS_SLUG = "fieldstone-apparel";
    static final String CURRENCY = "USD";
    static final String TIME_ZONE = "America/New_York";

    /** Inclusive range of generated sales. */
    static final LocalDate FIRST_SALE_DAY = LocalDate.of(2026, 3, 1);
    static final LocalDate LAST_SALE_DAY = LocalDate.of(2026, 8, 31);

    /** Selected products get a list-price increase from this date. */
    static final LocalDate PRICE_INCREASE_DAY = LocalDate.of(2026, 6, 1);

    /** Summer clearance on cold-weather categories. */
    static final LocalDate CLEARANCE_START = LocalDate.of(2026, 7, 15);
    static final BigDecimal CLEARANCE_MULTIPLIER = new BigDecimal("0.70");
    static final List<String> CLEARANCE_CATEGORIES = List.of("Outerwear", "Knitwear");

    private DemoCatalog() {
    }

    /**
     * @param expectedDailySales average sales on a weekday in March, before weekly and monthly effects
     * @param physical whether the store has opening hours (the online store sells around the clock)
     */
    record StoreSpec(String code, String name, String city, double expectedDailySales, boolean physical) {
    }

    /**
     * @param originalPrice price before {@link #PRICE_INCREASE_DAY}
     * @param currentPrice list price from {@link #PRICE_INCREASE_DAY}; equal to {@code originalPrice} when unchanged
     * @param popularity relative demand weight within the catalogue
     */
    record ProductSpec(
            String sku,
            String name,
            String category,
            BigDecimal originalPrice,
            BigDecimal currentPrice,
            double popularity) {

        /** The price charged on {@code day}, including increases and clearance discounts. */
        BigDecimal priceOn(LocalDate day) {
            BigDecimal price = day.isBefore(PRICE_INCREASE_DAY) ? originalPrice : currentPrice;
            if (!day.isBefore(CLEARANCE_START) && CLEARANCE_CATEGORIES.contains(category)) {
                price = price.multiply(CLEARANCE_MULTIPLIER);
            }
            return price.setScale(2, RoundingMode.HALF_UP);
        }
    }

    static final List<StoreSpec> STORES = List.of(
            new StoreSpec("BOS", "Back Bay", "Boston", 16, true),
            new StoreSpec("CAM", "Harvard Square", "Cambridge", 12, true),
            new StoreSpec("PVD", "Wickenden Street", "Providence", 8, true),
            new StoreSpec("WEB", "Online Store", null, 11, false));

    static final List<ProductSpec> PRODUCTS = List.of(
            product("TOP-001", "Everyday Cotton Tee", "Tops", "24.00", "26.00", 9),
            product("TOP-002", "Linen Camp Shirt", "Tops", "58.00", "58.00", 5),
            product("TOP-003", "Oxford Button-Down", "Tops", "64.00", "68.00", 6),
            product("TOP-004", "Striped Breton Top", "Tops", "42.00", "42.00", 5),
            product("BOT-001", "Slim Selvedge Jeans", "Bottoms", "98.00", "104.00", 7),
            product("BOT-002", "Relaxed Chino", "Bottoms", "72.00", "72.00", 6),
            product("BOT-003", "Pleated Wide-Leg Trouser", "Bottoms", "88.00", "88.00", 3),
            product("BOT-004", "Linen Drawstring Short", "Bottoms", "48.00", "48.00", 4),
            product("DRS-001", "Wrap Midi Dress", "Dresses", "118.00", "118.00", 4),
            product("DRS-002", "Linen Slip Dress", "Dresses", "96.00", "102.00", 4),
            product("DRS-003", "Poplin Shirt Dress", "Dresses", "108.00", "108.00", 3),
            product("OUT-001", "Waxed Field Jacket", "Outerwear", "189.00", "189.00", 3),
            product("OUT-002", "Lightweight Trench", "Outerwear", "219.00", "219.00", 2),
            product("OUT-003", "Quilted Liner Vest", "Outerwear", "89.00", "89.00", 3),
            product("KNT-001", "Merino Crewneck", "Knitwear", "94.00", "94.00", 4),
            product("KNT-002", "Cotton Cardigan", "Knitwear", "82.00", "82.00", 3),
            product("KNT-003", "Cashmere-Blend Hoodie", "Knitwear", "138.00", "138.00", 2),
            product("ACC-001", "Canvas Tote Bag", "Accessories", "36.00", "36.00", 5),
            product("ACC-002", "Leather Belt", "Accessories", "54.00", "54.00", 3),
            product("ACC-003", "Cotton Crew Socks (3-Pack)", "Accessories", "18.00", "20.00", 8),
            product("ACC-004", "Wool Blend Beanie", "Accessories", "28.00", "28.00", 2),
            product("FTW-001", "Canvas Low-Top Sneaker", "Footwear", "68.00", "68.00", 5),
            product("FTW-002", "Suede Chelsea Boot", "Footwear", "179.00", "179.00", 2),
            product("FTW-003", "Leather Slide Sandal", "Footwear", "62.00", "62.00", 3));

    /** Demand multiplier per category for March through August. */
    private static final Map<String, double[]> SEASONALITY = Map.of(
            "Tops", new double[] {0.9, 1.0, 1.1, 1.2, 1.2, 1.1},
            "Bottoms", new double[] {1.0, 1.0, 1.0, 1.1, 1.0, 1.1},
            "Dresses", new double[] {0.6, 0.9, 1.2, 1.5, 1.5, 1.2},
            "Outerwear", new double[] {1.6, 1.2, 0.6, 0.3, 0.4, 0.6},
            "Knitwear", new double[] {1.4, 1.1, 0.7, 0.4, 0.5, 0.8},
            "Accessories", new double[] {1.0, 1.0, 1.0, 1.0, 1.1, 1.1},
            "Footwear", new double[] {1.1, 1.0, 1.0, 1.1, 1.0, 1.0});

    /** Relative demand for {@code product} on {@code day}, combining popularity, season and clearance. */
    static double demandWeight(ProductSpec product, LocalDate day) {
        double[] byMonth = SEASONALITY.get(product.category());
        int index = Math.clamp(day.getMonthValue() - Month.MARCH.getValue(), 0, byMonth.length - 1);
        double weight = product.popularity() * byMonth[index];
        if (!day.isBefore(CLEARANCE_START) && CLEARANCE_CATEGORIES.contains(product.category())) {
            weight *= 2.2;
        }
        return weight;
    }

    private static ProductSpec product(
            String sku, String name, String category, String originalPrice, String currentPrice, double popularity) {
        return new ProductSpec(sku, name, category, new BigDecimal(originalPrice), new BigDecimal(currentPrice), popularity);
    }
}
