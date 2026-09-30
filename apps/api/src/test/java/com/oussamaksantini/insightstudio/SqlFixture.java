package com.oussamaksantini.insightstudio;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;

/** Inserts small, hand-computed datasets for integration tests using plain SQL. */
public final class SqlFixture {

    private final JdbcTemplate jdbc;

    public SqlFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clear() {
        jdbc.execute(PostgresIntegrationTest.TRUNCATE_ALL);
    }

    public long business(String name, String slug, String currency, String timeZone) {
        return insertId("INSERT INTO businesses (name, slug, currency, time_zone) VALUES (?, ?, ?, ?) RETURNING id",
                name, slug, currency, timeZone);
    }

    public long store(long businessId, String code, String name, String city) {
        return insertId("INSERT INTO stores (business_id, code, name, city) VALUES (?, ?, ?, ?) RETURNING id",
                businessId, code, name, city);
    }

    public long product(long businessId, String sku, String name, String category, String listPrice) {
        return insertId("INSERT INTO products (business_id, sku, name, category, list_price) VALUES (?, ?, ?, ?, ?) RETURNING id",
                businessId, sku, name, category, new BigDecimal(listPrice));
    }

    /** Inserts a sale; {@code lines} repeats (productId, quantity, unitPrice as a String). */
    public long sale(long storeId, String receipt, String soldAtUtc, Object... lines) {
        long saleId = insertId("INSERT INTO sales (store_id, receipt_number, sold_at) VALUES (?, ?, ?) RETURNING id",
                storeId, receipt, OffsetDateTime.parse(soldAtUtc));
        for (int i = 0; i < lines.length; i += 3) {
            jdbc.update("INSERT INTO sale_items (sale_id, product_id, quantity, unit_price) VALUES (?, ?, ?, ?)",
                    saleId, lines[i], lines[i + 1], new BigDecimal((String) lines[i + 2]));
        }
        return saleId;
    }

    private long insertId(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** Records an import batch for the business and attaches {@code saleIds} to it. */
    public long importBatch(long businessId, String fileName, String totalAmount, long... saleIds) {
        long batchId = insertId("""
                INSERT INTO import_batches
                    (business_id, file_name, content_sha256, status, row_count, sale_count, line_count, total_amount)
                VALUES (?, ?, ?, 'IMPORTED', ?, ?, ?, ?) RETURNING id
                """, businessId, fileName, "%064d".formatted(System.nanoTime()), saleIds.length, saleIds.length,
                saleIds.length, new BigDecimal(totalAmount));
        for (long saleId : saleIds) {
            jdbc.update("UPDATE sales SET import_batch_id = ? WHERE id = ?", batchId, saleId);
        }
        return batchId;
    }

    public long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
