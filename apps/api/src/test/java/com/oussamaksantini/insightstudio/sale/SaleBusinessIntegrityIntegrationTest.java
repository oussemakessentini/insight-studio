package com.oussamaksantini.insightstudio.sale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flyway V12: the database refuses any sale item whose sale and product belong to different
 * businesses, however the row is written or later changed, and keeps the derived business ids in
 * step with the store and the sale.
 */
class SaleBusinessIntegrityIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    SqlFixture db;
    long alpha;
    long bravo;
    long alphaStore;
    long bravoStore;
    long alphaProduct;
    long bravoProduct;
    long alphaSale;

    @BeforeEach
    void seed() {
        db = new SqlFixture(jdbc);
        db.clear();
        alpha = db.business("Alpha Co", "alpha-co", "EUR", "Europe/Paris");
        bravo = db.business("Bravo Co", "bravo-co", "USD", "America/New_York");
        alphaStore = db.store(alpha, "A1", "Alpha store", null);
        bravoStore = db.store(bravo, "B1", "Bravo store", null);
        alphaProduct = db.product(alpha, "A-TEE", "Alpha tee", "Tops", "20.00");
        bravoProduct = db.product(bravo, "B-TEE", "Bravo tee", "Tops", "30.00");
        alphaSale = db.sale(alphaStore, "A-1", "2026-06-01T10:00:00Z", alphaProduct, 2, "18.00");
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    void derivedBusinessIdsFollowTheStoreAndTheSale() {
        assertThat(jdbc.queryForObject("SELECT business_id FROM sales WHERE id = ?", Long.class, alphaSale)).isEqualTo(alpha);
        assertThat(jdbc.queryForObject("SELECT business_id FROM sale_items WHERE sale_id = ?", Long.class, alphaSale))
                .isEqualTo(alpha);
        // A business id supplied by the caller is ignored: it always comes from the parent row.
        long sale = jdbc.queryForObject(
                "INSERT INTO sales (store_id, receipt_number, sold_at, business_id) VALUES (?, 'A-2', now(), ?) RETURNING id",
                Long.class, alphaStore, bravo);
        assertThat(jdbc.queryForObject("SELECT business_id FROM sales WHERE id = ?", Long.class, sale)).isEqualTo(alpha);
    }

    @Test
    void aSaleItemCannotReferenceAnotherBusinesssProduct() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sale_items (sale_id, product_id, quantity, unit_price) VALUES (?, ?, 1, 30.00)",
                alphaSale, bravoProduct))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_sale_items_product_business");
        // Not even by naming the other business explicitly.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sale_items (sale_id, product_id, quantity, unit_price, business_id) VALUES (?, ?, 1, 30.00, ?)",
                alphaSale, bravoProduct, bravo))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_sale_items_product_business");
        assertThat(count("sale_items")).isEqualTo(1);
    }

    @Test
    void existingRowsCannotBeChangedIntoACrossBusinessReference() {
        // An item switched to another business's product.
        assertThatThrownBy(() -> jdbc.update("UPDATE sale_items SET product_id = ? WHERE sale_id = ?", bravoProduct, alphaSale))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A sale moved to another business's store (its items' products stay with Alpha).
        assertThatThrownBy(() -> jdbc.update("UPDATE sales SET store_id = ? WHERE id = ?", bravoStore, alphaSale))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A store with sales moved to another business.
        assertThatThrownBy(() -> jdbc.update("UPDATE stores SET business_id = ? WHERE id = ?", bravo, alphaStore))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A product that has been sold moved to another business.
        assertThatThrownBy(() -> jdbc.update("UPDATE products SET business_id = ? WHERE id = ?", bravo, alphaProduct))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sale_items si JOIN sales s ON s.id = si.sale_id JOIN stores st ON st.id = s.store_id "
                        + "JOIN products p ON p.id = si.product_id WHERE p.business_id <> st.business_id", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT product_id FROM sale_items WHERE sale_id = ?", Long.class, alphaSale))
                .isEqualTo(alphaProduct);
    }

    @Test
    void consistentMovesAndDeletesStillWork() {
        // A store without sales may move; a sale may move to another store of its business.
        long emptyStore = db.store(alpha, "A2", "New store", null);
        jdbc.update("UPDATE stores SET business_id = ? WHERE id = ?", bravo, emptyStore);
        long secondAlphaStore = db.store(alpha, "A3", "Second store", null);
        jdbc.update("UPDATE sales SET store_id = ? WHERE id = ?", secondAlphaStore, alphaSale);
        assertThat(jdbc.queryForObject("SELECT business_id FROM sales WHERE id = ?", Long.class, alphaSale)).isEqualTo(alpha);
        // Deleting a sale still deletes its items.
        jdbc.update("DELETE FROM sales WHERE id = ?", alphaSale);
        assertThat(count("sale_items")).isZero();
        assertThat(db.sale(bravoStore, "B-1", "2026-06-02T10:00:00Z", bravoProduct, 1, "30.00")).isPositive();
        assertThat(jdbc.queryForObject("SELECT SUM(quantity * unit_price) FROM sale_items WHERE business_id = ?",
                BigDecimal.class, bravo)).isEqualByComparingTo("30.00");
    }
}
