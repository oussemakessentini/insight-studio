package com.oussamaksantini.insightstudio.importing;

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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Import lookups and writes, all scoped to one business. Writes use plain JDBC (the JPA entities
 * don't map {@code sales.import_batch_id}).
 */
@Repository
class ImportQueries {

    /** Rows per set-based insert statement. */
    private static final int CHUNK_SIZE = 10_000;
    /** Receipt keys per lookup query, well below PostgreSQL's bind-parameter limit. */
    private static final int LOOKUP_CHUNK = 2000;

    private final NamedParameterJdbcTemplate jdbc;

    ImportQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Summary values stored with a batch. */
    record NewBatch(long businessId, String fileName, String contentSha256, int rowCount, int saleCount,
            int lineCount, BigDecimal totalAmount) {
    }

    /** Serialises imports into one business until the current transaction ends. */
    void lockBusiness(long businessId) {
        jdbc.queryForObject("SELECT id FROM businesses WHERE id = :id FOR UPDATE", Map.of("id", businessId), Long.class);
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

    boolean hashExists(long businessId, String contentSha256) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM import_batches WHERE business_id = :businessId AND content_sha256 = :sha)",
                Map.of("businessId", businessId, "sha", contentSha256), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** Inserts the batch, its receipts and their line items. Must run inside a transaction. */
    long insertBatch(NewBatch batch, List<PlannedReceipt> receipts) {
        long batchId = jdbc.queryForObject("""
                INSERT INTO import_batches
                    (business_id, file_name, content_sha256, status, row_count, sale_count, line_count, total_amount)
                VALUES (:businessId, :fileName, :sha, 'IMPORTED', :rowCount, :saleCount, :lineCount, :totalAmount)
                RETURNING id
                """,
                new MapSqlParameterSource()
                        .addValue("businessId", batch.businessId())
                        .addValue("fileName", batch.fileName())
                        .addValue("sha", batch.contentSha256())
                        .addValue("rowCount", batch.rowCount())
                        .addValue("saleCount", batch.saleCount())
                        .addValue("lineCount", batch.lineCount())
                        .addValue("totalAmount", batch.totalAmount()),
                Long.class);

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
        return batchId;
    }

    long countBatches(long businessId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM import_batches WHERE business_id = :businessId",
                Map.of("businessId", businessId), Long.class);
    }

    List<ImportBatchSummary> pageBatches(long businessId, int limit, long offset) {
        String sql = """
                SELECT id, file_name, row_count, sale_count, line_count, total_amount, created_at
                FROM import_batches
                WHERE business_id = :businessId
                ORDER BY created_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("businessId", businessId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(sql, params, (rs, i) -> new ImportBatchSummary(
                rs.getLong("id"),
                rs.getString("file_name"),
                rs.getInt("row_count"),
                rs.getInt("sale_count"),
                rs.getInt("line_count"),
                rs.getBigDecimal("total_amount"),
                instant(rs, "created_at")));
    }

    Optional<ImportDetailResponse> detail(long batchId, long businessId) {
        String sql = """
                SELECT b.id, b.file_name, b.row_count, b.sale_count, b.line_count, b.total_amount, b.created_at,
                       (SELECT MIN(s.sold_at) FROM sales s WHERE s.import_batch_id = b.id) AS first_sold_at,
                       (SELECT MAX(s.sold_at) FROM sales s WHERE s.import_batch_id = b.id) AS last_sold_at
                FROM import_batches b
                WHERE b.id = :batchId AND b.business_id = :businessId
                """;
        return jdbc.query(sql, Map.of("batchId", batchId, "businessId", businessId), (rs, i) -> new ImportDetailResponse(
                        rs.getLong("id"),
                        rs.getString("file_name"),
                        rs.getInt("row_count"),
                        rs.getInt("sale_count"),
                        rs.getInt("line_count"),
                        rs.getBigDecimal("total_amount"),
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

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
