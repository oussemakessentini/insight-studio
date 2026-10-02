package com.oussamaksantini.insightstudio.importing.dto;

import com.oussamaksantini.insightstudio.importing.ImportKind;
import com.oussamaksantini.insightstudio.importing.ImportMode;
import com.oussamaksantini.insightstudio.importing.ImportStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * An import attempt, as listed in the import history: {@code IMPORTED} with its counts, or
 * {@code REJECTED} with its error count (nothing of it was written). The sales fields are 0 for
 * stores and products.
 *
 * @param importedBy display name of the member who uploaded the file, or {@code null} when unknown
 */
public record ImportBatchSummary(
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
        Instant createdAt) {
}
