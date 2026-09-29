package com.oussamaksantini.insightstudio.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.business.BusinessRepository;
import com.oussamaksantini.insightstudio.product.ProductRepository;
import com.oussamaksantini.insightstudio.store.StoreRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the seeder explicitly against an empty database. The shared test context has no active
 * profile, which also proves the seeder is not registered unless {@code demo} is enabled.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoDataSeederTest extends PostgresIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    BusinessRepository businesses;

    @Autowired
    StoreRepository stores;

    @Autowired
    ProductRepository products;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    DemoDataSeeder seeder;

    @BeforeAll
    void seedEmptyDatabase() {
        jdbc.execute(TRUNCATE_ALL);
        seeder = new DemoDataSeeder(businesses, stores, products, jdbc, transaction);
        assertThat(seeder.seed()).isTrue();
    }

    @Test
    void seederIsNotActiveWithoutDemoProfile() {
        assertThat(context.getBeansOfType(DemoDataSeeder.class)).isEmpty();
    }

    @Test
    void seedsTheSameDatasetEveryTime() {
        assertThat(count("businesses")).isEqualTo(1);
        assertThat(count("stores")).isEqualTo(DemoCatalog.STORES.size());
        assertThat(count("products")).isEqualTo(DemoCatalog.PRODUCTS.size());
        // Fixed seed and dates: these change only if the generator itself changes.
        assertThat(count("sales")).isEqualTo(10_397);
        assertThat(count("sale_items")).isEqualTo(17_993);
        assertThat(jdbc.queryForObject("SELECT SUM(quantity * unit_price) FROM sale_items", BigDecimal.class))
                .isEqualByComparingTo("1555513.20");
    }

    @Test
    void secondRunDoesNotDuplicateData() {
        long sales = count("sales");

        assertThat(seeder.seed()).isFalse();
        assertThat(count("sales")).isEqualTo(sales);
    }

    @Test
    void salesKeepHistoricalPricesWhileProductsHoldCurrentPrice() {
        BigDecimal listPrice = jdbc.queryForObject(
                "SELECT list_price FROM products WHERE sku = 'BOT-001'", BigDecimal.class);
        var pricesCharged = jdbc.queryForList("""
                SELECT DISTINCT si.unit_price FROM sale_items si JOIN products p ON p.id = si.product_id
                WHERE p.sku = 'BOT-001' ORDER BY 1
                """, BigDecimal.class);

        assertThat(listPrice).isEqualByComparingTo("104.00");
        assertThat(pricesCharged).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("98.00"), new BigDecimal("104.00"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
