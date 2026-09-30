package com.oussamaksantini.insightstudio.importing.dto;

import com.oussamaksantini.insightstudio.importing.ImportStatus;
import java.math.BigDecimal;
import java.util.List;

/**
 * Result of validating (and, unless it was a dry run, importing) a CSV file. Validation errors are
 * a normal outcome ({@link ImportStatus#REJECTED}), not an HTTP error.
 *
 * @param batchId the new import batch, or {@code null} unless {@code status} is {@code IMPORTED}
 * @param rowCount data rows in the file (the header excluded)
 * @param saleCount distinct receipts, i.e. (store_code, receipt_number) pairs, among well-formed rows
 * @param lineCount line items among well-formed rows
 * @param totalAmount sum of quantity x unit_price over well-formed rows
 * @param errors the first {@value com.oussamaksantini.insightstudio.importing.ImportService#MAX_REPORTED_ERRORS} errors, by line
 * @param errorCount total number of errors found, which may exceed the errors listed
 */
public record ImportResult(
        Long batchId,
        ImportStatus status,
        boolean dryRun,
        String fileName,
        int rowCount,
        int saleCount,
        int lineCount,
        BigDecimal totalAmount,
        List<ImportError> errors,
        int errorCount) {
}
