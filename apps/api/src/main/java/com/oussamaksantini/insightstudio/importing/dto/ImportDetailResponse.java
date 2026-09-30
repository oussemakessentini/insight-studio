package com.oussamaksantini.insightstudio.importing.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One import batch.
 *
 * @param firstSoldAt earliest {@code sold_at} among the batch's receipts
 * @param lastSoldAt latest {@code sold_at} among the batch's receipts
 */
public record ImportDetailResponse(
        long batchId,
        String fileName,
        int rowCount,
        int saleCount,
        int lineCount,
        BigDecimal totalAmount,
        Instant createdAt,
        Instant firstSoldAt,
        Instant lastSoldAt) {
}
