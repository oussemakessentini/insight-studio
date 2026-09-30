package com.oussamaksantini.insightstudio.importing.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** A successful import, as listed in the import history. */
public record ImportBatchSummary(
        long batchId,
        String fileName,
        int rowCount,
        int saleCount,
        int lineCount,
        BigDecimal totalAmount,
        Instant createdAt) {
}
