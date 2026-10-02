package com.oussamaksantini.insightstudio.importing.dto;

import com.oussamaksantini.insightstudio.importing.ImportKind;
import com.oussamaksantini.insightstudio.importing.ImportMode;
import com.oussamaksantini.insightstudio.importing.ImportStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One import attempt, with the fields of {@link ImportBatchSummary}.
 *
 * @param firstSoldAt sales imports only: earliest {@code sold_at} among the batch's receipts ({@code null} otherwise)
 * @param lastSoldAt sales imports only: latest {@code sold_at} among the batch's receipts ({@code null} otherwise)
 */
public record ImportDetailResponse(
        long batchId,
        ImportKind kind,
        ImportMode mode,
        ImportStatus status,
        String fileName,
        int rowCount,
        int saleCount,
        int lineCount,
        BigDecimal totalAmount,
        int created,
        int updated,
        int unchanged,
        int errorCount,
        String importedBy,
        Instant createdAt,
        Instant firstSoldAt,
        Instant lastSoldAt) {
}
