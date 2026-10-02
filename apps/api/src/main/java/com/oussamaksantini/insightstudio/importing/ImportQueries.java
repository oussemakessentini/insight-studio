package com.oussamaksantini.insightstudio.importing;

import com.oussamaksantini.insightstudio.importing.CatalogValidator.ExistingProduct;
import com.oussamaksantini.insightstudio.importing.CatalogValidator.ExistingStore;
import com.oussamaksantini.insightstudio.importing.CatalogValidator.ProductRow;
import com.oussamaksantini.insightstudio.importing.CatalogValidator.StoreRow;
import com.oussamaksantini.insightstudio.importing.ImportValidator.PlannedLine;
import com.oussamaksantini.insightstudio.importing.ImportValidator.PlannedReceipt;
import com.oussamaksantini.insightstudio.importing.ImportValidator.ReceiptKey;
import com.oussamaksantini.insightstudio.importing.dto.ImportBatchSummary;
import com.oussamaksantini.insightstudio.importing.dto.ImportDetailResponse;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Import lookups and writes, all scoped to one business. Writes use plain JDBC (the JPA entities
 * don't map {@code sales.import_batch_id}) with set-based statements over arrays.
 */
@Repository
class ImportQueries {

    /** Rows per set-based insert statement. */
    private static final int CHUNK_SIZE = 10_000;
    /** Receipt keys per lookup query, well below PostgreSQL's bind-parameter limit. */
    private static final int LOOKUP_CHUNK = 2000;

    /** The history columns shared by the list and the detail. */
    private static final String BATCH_COLUMNS = """
            b.id, b.kind, b.mode, b.status, b.file_name, b.row_count, b.sale_count, b.line_count, b.total_amount,
            b.created_count, b.updated_count, b.unchanged_count, b.error_count, u.display_name AS imported_by, b.created_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    ImportQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A row of {@code import_batches}: an imported file with its counts, or a rejected attempt with
     * its error count (and zero counts otherwise).
     *
     * @param createdBy the member who uploaded the file, or {@code null}
     */
    record NewBatch(long businessId, ImportKind kind, ImportMode mode, ImportStatus status, String fileName,
            String contentSha256, int rowCount, int saleCount, int lineCount, BigDecimal totalAmount, int created,
            int updated, int unchanged, int errorCount, Long createdBy) {
    }

    /**
     * Serialises imports into one business until the current transaction ends (a transaction-scoped
     * advisory lock: other businesses' imports and other writes to the business are not blocked).
     */
    void lockImports(long businessId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))", Map.of("key", "import:" + businessId),
                (ResultSetExtractor<Void>) rs -> null);
    }

    Map<String, Long> storeIdsByCode(long businessId) {
        Map<String, Long> ids = new HashMap<>();
        jdbc.query("SELECT id, code FROM stores WHERE business_id = :businessId", Map.of("businessId", businessId),
                rs -> {
                    ids.put(rs.getString("code"), rs.getLong("id"));
                });
        return ids;
    }

    Map<String, Long> productIdsBySku(long businessId) {
        Map<String, Long> ids = new HashMap<>();
        jdbc.query("SELECT id, sku FROM products WHERE business_id = :businessId", Map.of("businessId", businessId),
                rs -> {
                    ids.put(rs.getString("sku"), rs.getLong("id"));
                });
        return ids;
    }

    Map<String, ExistingStore> storesByCode(long businessId) {
        Map<String, ExistingStore> stores = new HashMap<>();
        jdbc.query("SELECT id, code, name, city FROM stores WHERE business_id = :businessId",
                Map.of("businessId", businessId), rs -> {
                    stores.put(rs.getString("code"), new ExistingStore(
                            rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("city")));
                });
        return stores;
    }

    Map<String, ExistingProduct> productsBySku(long businessId) {
        Map<String, ExistingProduct> products = new HashMap<>();
        jdbc.query("SELECT id, sku, name, category, list_price FROM products WHERE business_id = :businessId",
                Map.of("businessId", businessId), rs -> {
                    products.put(rs.getString("sku"), new ExistingProduct(rs.getLong("id"), rs.getString("sku"),
                            rs.getString("name"), rs.getString("category"), rs.getBigDecimal("list_price")));
                });
        return products;
    }

    /** Which of {@code keys} already exist. Store ids come from this business, so the check is business-scoped. */
    Set<ReceiptKey> existingReceipts(Collection<ReceiptKey> keys) {
        Set<ReceiptKey> existing = new HashSet<>();
        List<ReceiptKey> all = new ArrayList<>(keys);
        for (int from = 0; from < all.size(); from += LOOKUP_CHUNK) {
            List<ReceiptKey> chunk = all.subList(from, Math.min(from + LOOKUP_CHUNK, all.size()));
            Set<ReceiptKey> wanted = new HashSet<>(chunk);
            String sql = """
                    SELECT store_id, receipt_number
                    FROM sales
                    WHERE store_id IN (:storeIds) AND receipt_number IN (:numbers)
                    """;
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("storeIds", chunk.stream().map(ReceiptKey::storeId).distinct().toList())
                    .addValue("numbers", chunk.stream().map(ReceiptKey::receiptNumber).distinct().toList());
            jdbc.query(sql, params, rs -> {
                // The IN lists match any store/number combination; keep only the exact pairs asked for.
                ReceiptKey key = new ReceiptKey(rs.getLong("store_id"), rs.getString("receipt_number"));
                if (wanted.contains(key)) {
                    existing.add(key);
                }
            });
        }
        return existing;
    }

    /** When a file with this content was first imported as {@code kind} into the business, if ever (rejected attempts don't count). */
    Optional<Instant> importedAt(long businessId, ImportKind kind, String contentSha256) {
        String sql = """
                SELECT MIN(created_at) AS imported_at
                FROM import_batches
                WHERE business_id = :businessId AND kind = :kind AND content_sha256 = :sha AND status = 'IMPORTED'
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("businessId", businessId)
                .addValue("kind", kind.param())
                .addValue("sha", contentSha256);
        return Optional.ofNullable(jdbc.queryForObject(sql, params, (rs, i) -> instant(rs, "imported_at")));
    }

    /** Inserts a history row and returns its id. */
    long insertBatch(NewBatch batch) {
        return jdbc.queryForObject("""
                INSERT INTO import_batches
                    (business_id, kind, mode, status, file_name, content_sha256, row_count, sale_count, line_count,
                     total_amount, created_count, updated_count, unchanged_count, error_count, created_by)
                VALUES (:businessId, :kind, :mode, :status, :fileName, :sha, :rowCount, :saleCount, :lineCount,
                        :totalAmount, :created, :updated, :unchanged, :errorCount, :createdBy)
                RETURNING id
                """,
                new MapSqlParameterSource()
                        .addValue("businessId", batch.businessId())
                        .addValue("kind", batch.kind().param())
                        .addValue("mode", batch.mode().param())
                        .addValue("status", batch.status().name())
                        .addValue("fileName", batch.fileName())
                        .addValue("sha", batch.contentSha256())
                        .addValue("rowCount", batch.rowCount())
                        .addValue("saleCount", batch.saleCount())
                        .addValue("lineCount", batch.lineCount())
                        .addValue("totalAmount", batch.totalAmount())
                        .addValue("created", batch.created())
                        .addValue("updated", batch.updated())
                        .addValue("unchanged", batch.unchanged())
                        .addValue("errorCount", batch.errorCount())
                        .addValue("createdBy", batch.createdBy()),
                Long.class);
    }

    /** Inserts receipts and their line items for a batch. Must run inside a transaction. */
    void insertReceipts(long batchId, List<PlannedReceipt> receipts) {
        Map<ReceiptKey, Long> saleIds = new HashMap<>();
        for (int from = 0; from < receipts.size(); from += CHUNK_SIZE) {
            saleIds.putAll(insertSales(batchId, receipts.subList(from, Math.min(from + CHUNK_SIZE, receipts.size()))));
        }

        List<Object[]> items = new ArrayList<>();
        for (PlannedReceipt receipt : receipts) {
            long saleId = saleIds.get(receipt.key());
            for (PlannedLine line : receipt.lines()) {
                items.add(new Object[] {saleId, line.productId(), line.quantity(), line.unitPrice().toPlainString()});
            }
        }
        for (int from = 0; from < items.size(); from += CHUNK_SIZE) {
            insertItems(items.subList(from, Math.min(from + CHUNK_SIZE, items.size())));
        }
    }

    /**
     * Creates stores. A code taken meanwhile violates {@code uq_stores_business_code}, which rolls
     * the import back.
     */
    void insertStores(long businessId, List<StoreRow> stores) {
        if (stores.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO stores (business_id, code, name, city)
                SELECT ?, u.code, u.name, u.city
                FROM unnest(?, ?, ?) AS u(code, name, city)
                """;
        jdbc.getJdbcTemplate().execute((ConnectionCallback<Integer>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setLong(1, businessId);
                ps.setArray(2, con.createArrayOf("varchar", stores.stream().map(StoreRow::code).toArray()));
                ps.setArray(3, con.createArrayOf("varchar", stores.stream().map(StoreRow::name).toArray()));
                ps.setArray(4, con.createArrayOf("varchar", stores.stream().map(StoreRow::city).toArray()));
                return ps.executeUpdate();
            }
        });
    }

    /** Sets the name and city of existing stores of the business; nothing else of theirs changes. */
    void updateStores(long businessId, List<StoreRow> stores) {
        if (stores.isEmpty()) {
            return;
        }
        String sql = """
                UPDATE stores s
                SET name = u.name, city = u.city
                FROM unnest(?, ?, ?) AS u(id, name, city)
                WHERE s.id = u.id AND s.business_id = ?
                """;
        jdbc.getJdbcTemplate().execute((ConnectionCallback<Integer>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setArray(1, con.createArrayOf("int8", stores.stream().map(StoreRow::id).toArray()));
                ps.setArray(2, con.createArrayOf("varchar", stores.stream().map(StoreRow::name).toArray()));
                ps.setArray(3, con.createArrayOf("varchar", stores.stream().map(StoreRow::city).toArray()));
                ps.setLong(4, businessId);
                return ps.executeUpdate();
            }
        });
    }

    /**
     * Creates products. A SKU taken meanwhile violates {@code uq_products_business_sku}, which rolls
     * the import back.
     */
    void insertProducts(long businessId, List<ProductRow> products) {
        if (products.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO products (business_id, sku, name, category, list_price)
                SELECT ?, u.sku, u.name, u.category, CAST(u.list_price AS NUMERIC(12,2))
                FROM unnest(?, ?, ?, ?) AS u(sku, name, category, list_price)
                """;
        jdbc.getJdbcTemplate().execute((ConnectionCallback<Integer>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setLong(1, businessId);
                ps.setArray(2, con.createArrayOf("varchar", products.stream().map(ProductRow::sku).toArray()));
                ps.setArray(3, con.createArrayOf("varchar", products.stream().map(ProductRow::name).toArray()));
                ps.setArray(4, con.createArrayOf("varchar", products.stream().map(ProductRow::category).toArray()));
                ps.setArray(5, con.createArrayOf("text", products.stream().map(p -> p.listPrice().toPlainString()).toArray()));
                return ps.executeUpdate();
            }
        });
    }

    /**
     * Sets the name, category and list price of existing products of the business. Sale items keep
     * the price actually charged ({@code sale_items.unit_price}), so past revenue never changes.
     */
    void updateProducts(long businessId, List<ProductRow> products) {
        if (products.isEmpty()) {
            return;
        }
        String sql = """
                UPDATE products p
                SET name = u.name, category = u.category, list_price = CAST(u.list_price AS NUMERIC(12,2))
                FROM unnest(?, ?, ?, ?) AS u(id, name, category, list_price)
                WHERE p.id = u.id AND p.business_id = ?
                """;
        jdbc.getJdbcTemplate().execute((ConnectionCallback<Integer>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setArray(1, con.createArrayOf("int8", products.stream().map(ProductRow::id).toArray()));
                ps.setArray(2, con.createArrayOf("varchar", products.stream().map(ProductRow::name).toArray()));
                ps.setArray(3, con.createArrayOf("varchar", products.stream().map(ProductRow::category).toArray()));
                ps.setArray(4, con.createArrayOf("text", products.stream().map(p -> p.listPrice().toPlainString()).toArray()));
                ps.setLong(5, businessId);
                return ps.executeUpdate();
            }
        });
    }

    /** @param kind only this kind, or every kind when {@code null} */
    long countBatches(long businessId, ImportKind kind) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("businessId", businessId)
                .addValue("kind", kind == null ? null : kind.param());
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM import_batches
                WHERE business_id = :businessId AND (CAST(:kind AS VARCHAR) IS NULL OR kind = :kind)
                """, params, Long.class);
    }

    /** @param kind only this kind, or every kind when {@code null} */
    List<ImportBatchSummary> pageBatches(long businessId, ImportKind kind, int limit, long offset) {
        String sql = "SELECT " + BATCH_COLUMNS + """
                FROM import_batches b
                LEFT JOIN users u ON u.id = b.created_by
                WHERE b.business_id = :businessId AND (CAST(:kind AS VARCHAR) IS NULL OR b.kind = :kind)
                ORDER BY b.created_at DESC, b.id DESC
                LIMIT :limit OFFSET :offset
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("businessId", businessId)
                .addValue("kind", kind == null ? null : kind.param())
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(sql, params, (rs, i) -> new ImportBatchSummary(
                rs.getLong("id"),
                kind(rs),
                ImportMode.fromParam(rs.getString("mode")),
                ImportStatus.valueOf(rs.getString("status")),
                rs.getString("file_name"),
                rs.getInt("row_count"),
                rs.getInt("sale_count"),
                rs.getInt("line_count"),
                rs.getBigDecimal("total_amount"),
                rs.getInt("created_count"),
                rs.getInt("updated_count"),
                rs.getInt("unchanged_count"),
                rs.getInt("error_count"),
                rs.getString("imported_by"),
                instant(rs, "created_at")));
    }

    Optional<ImportDetailResponse> detail(long batchId, long businessId) {
        String sql = "SELECT " + BATCH_COLUMNS + """
                       , (SELECT MIN(s.sold_at) FROM sales s WHERE s.import_batch_id = b.id) AS first_sold_at,
                       (SELECT MAX(s.sold_at) FROM sales s WHERE s.import_batch_id = b.id) AS last_sold_at
                FROM import_batches b
                LEFT JOIN users u ON u.id = b.created_by
                WHERE b.id = :batchId AND b.business_id = :businessId
                """;
        return jdbc.query(sql, Map.of("batchId", batchId, "businessId", businessId), (rs, i) -> new ImportDetailResponse(
                        rs.getLong("id"),
                        kind(rs),
                        ImportMode.fromParam(rs.getString("mode")),
                        ImportStatus.valueOf(rs.getString("status")),
                        rs.getString("file_name"),
                        rs.getInt("row_count"),
                        rs.getInt("sale_count"),
                        rs.getInt("line_count"),
                        rs.getBigDecimal("total_amount"),
                        rs.getInt("created_count"),
                        rs.getInt("updated_count"),
                        rs.getInt("unchanged_count"),
                        rs.getInt("error_count"),
                        rs.getString("imported_by"),
                        instant(rs, "created_at"),
                        instant(rs, "first_sold_at"),
                        instant(rs, "last_sold_at")))
                .stream()
                .findFirst();
    }

    /**
     * Inserts receipts with one set-based statement ({@code unnest} over arrays): far fewer round
     * trips than a JDBC batch of single-row inserts. Returns the new sale ids by receipt.
     */
    private Map<ReceiptKey, Long> insertSales(long batchId, List<PlannedReceipt> receipts) {
        String sql = """
                INSERT INTO sales (store_id, receipt_number, sold_at, import_batch_id)
                SELECT u.store_id, u.receipt_number, CAST(u.sold_at AS TIMESTAMPTZ), ?
                FROM unnest(?, ?, ?) AS u(store_id, receipt_number, sold_at)
                RETURNING id, store_id, receipt_number
                """;
        return jdbc.getJdbcTemplate().execute((ConnectionCallback<Map<ReceiptKey, Long>>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setLong(1, batchId);
                ps.setArray(2, con.createArrayOf("int8", receipts.stream().map(r -> r.key().storeId()).toArray()));
                ps.setArray(3, con.createArrayOf("varchar", receipts.stream().map(r -> r.key().receiptNumber()).toArray()));
                // ISO-8601 text in UTC, cast to TIMESTAMPTZ by PostgreSQL.
                ps.setArray(4, con.createArrayOf("text", receipts.stream().map(r -> r.soldAt().toString()).toArray()));
                Map<ReceiptKey, Long> ids = new HashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.put(new ReceiptKey(rs.getLong("store_id"), rs.getString("receipt_number")), rs.getLong("id"));
                    }
                }
                return ids;
            }
        });
    }

    /** Inserts line items given as (saleId, productId, quantity, unitPrice as plain text). */
    private void insertItems(List<Object[]> items) {
        String sql = """
                INSERT INTO sale_items (sale_id, product_id, quantity, unit_price)
                SELECT u.sale_id, u.product_id, u.quantity, CAST(u.unit_price AS NUMERIC(12,2))
                FROM unnest(?, ?, ?, ?) AS u(sale_id, product_id, quantity, unit_price)
                """;
        jdbc.getJdbcTemplate().execute((ConnectionCallback<Integer>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setArray(1, con.createArrayOf("int8", items.stream().map(i -> i[0]).toArray()));
                ps.setArray(2, con.createArrayOf("int8", items.stream().map(i -> i[1]).toArray()));
                ps.setArray(3, con.createArrayOf("int4", items.stream().map(i -> i[2]).toArray()));
                ps.setArray(4, con.createArrayOf("text", items.stream().map(i -> i[3]).toArray()));
                return ps.executeUpdate();
            }
        });
    }

    private static ImportKind kind(ResultSet rs) throws SQLException {
        String kind = rs.getString("kind");
        return ImportKind.fromParam(kind).orElseThrow(() -> new IllegalStateException("Unknown import kind " + kind));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
