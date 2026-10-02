package com.oussamaksantini.insightstudio.sale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Flyway V12 on a database that already holds a cross-business sale item: the migration stops with
 * an explanation and changes nothing (no row deleted or rewritten); once the row is corrected by
 * hand, it applies.
 */
class SaleBusinessMigrationTest {

    private static PostgreSQLContainer postgres;
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).withStartupTimeout(Duration.ofMinutes(3));
        postgres.start();
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    private static long insert(String sql, Object... args) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class, args);
    }

    @Test
    void stopsOnCrossBusinessItemsWithoutChangingThemThenAppliesOnceFixed() {
        flyway("11").migrate();
        long alpha = insert("INSERT INTO businesses (name, slug, currency, time_zone) VALUES ('Alpha', 'alpha', 'EUR', 'UTC')");
        long bravo = insert("INSERT INTO businesses (name, slug, currency, time_zone) VALUES ('Bravo', 'bravo', 'EUR', 'UTC')");
        long alphaStore = insert("INSERT INTO stores (business_id, code, name) VALUES (?, 'A1', 'Alpha')", alpha);
        long alphaProduct = insert("INSERT INTO products (business_id, sku, name, category, list_price) "
                + "VALUES (?, 'A-1', 'A', 'Tops', 10)", alpha);
        long bravoProduct = insert("INSERT INTO products (business_id, sku, name, category, list_price) "
                + "VALUES (?, 'B-1', 'B', 'Tops', 10)", bravo);
        long sale = insert("INSERT INTO sales (store_id, receipt_number, sold_at) VALUES (?, 'R-1', now())", alphaStore);
        jdbc.update("INSERT INTO sale_items (sale_id, product_id, quantity, unit_price) VALUES (?, ?, 1, 10)", sale, alphaProduct);
        // The bad row: Alpha's sale, Bravo's product (possible before V12).
        long bad = insert("INSERT INTO sale_items (sale_id, product_id, quantity, unit_price) VALUES (?, ?, 2, 7.50)",
                sale, bravoProduct);

        assertThatThrownBy(() -> flyway("latest").migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V12 stopped: 1 sale item(s) reference a product of another business")
                .hasMessageContaining("Nothing was changed");

        // Nothing was deleted, rewritten or half-applied.
        assertThat(jdbc.queryForObject("SELECT product_id FROM sale_items WHERE id = ?", Long.class, bad)).isEqualTo(bravoProduct);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sale_items", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'sale_items' AND column_name = 'business_id'",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT MAX(version::int) FROM flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(11);

        // An operator corrects the row (here: the right product); the migration then applies.
        long alphaBottoms = insert("INSERT INTO products (business_id, sku, name, category, list_price) "
                + "VALUES (?, 'A-2', 'A2', 'Bottoms', 7.50)", alpha);
        jdbc.update("UPDATE sale_items SET product_id = ? WHERE id = ?", alphaBottoms, bad);
        flyway("latest").repair();
        flyway("latest").migrate();
        assertThat(jdbc.queryForList("SELECT business_id FROM sale_items", Long.class)).containsOnly(alpha);
        assertThat(jdbc.queryForObject("SELECT business_id FROM sales WHERE id = ?", Long.class, sale)).isEqualTo(alpha);
    }
}
