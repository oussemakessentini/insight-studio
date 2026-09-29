package com.oussamaksantini.insightstudio.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.oussamaksantini.insightstudio.demo.DemoCatalog.ProductSpec;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DemoCatalogTest {

    private static ProductSpec product(String sku) {
        return DemoCatalog.PRODUCTS.stream().filter(p -> p.sku().equals(sku)).findFirst().orElseThrow();
    }

    @Test
    void priceIncreaseAppliesFromJuneFirst() {
        ProductSpec jeans = product("BOT-001");

        assertThat(jeans.priceOn(LocalDate.of(2026, 5, 31))).isEqualByComparingTo("98.00");
        assertThat(jeans.priceOn(LocalDate.of(2026, 6, 1))).isEqualByComparingTo("104.00");
    }

    @Test
    void clearanceDiscountsColdWeatherCategoriesOnly() {
        ProductSpec jacket = product("OUT-001");
        ProductSpec tee = product("TOP-001");

        assertThat(jacket.priceOn(LocalDate.of(2026, 7, 14))).isEqualByComparingTo("189.00");
        assertThat(jacket.priceOn(LocalDate.of(2026, 7, 15))).isEqualByComparingTo("132.30");
        assertThat(tee.priceOn(LocalDate.of(2026, 7, 15))).isEqualByComparingTo("26.00");
    }

    @Test
    void skusAreUnique() {
        assertThat(DemoCatalog.PRODUCTS).extracting(ProductSpec::sku).doesNotHaveDuplicates();
        assertThat(DemoCatalog.STORES).extracting(DemoCatalog.StoreSpec::code).doesNotHaveDuplicates();
    }
}
