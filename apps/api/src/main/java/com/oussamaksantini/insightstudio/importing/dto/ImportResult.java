package com.oussamaksantini.insightstudio.importing.dto;

import com.oussamaksantini.insightstudio.importing.ImportKind;
import com.oussamaksantini.insightstudio.importing.ImportMode;
import com.oussamaksantini.insightstudio.importing.ImportStatus;
import java.math.BigDecimal;
import java.util.List;

/**
 * Result of validating (and, unless it was a dry run, importing) a CSV file. Validation errors are
 * a normal outcome ({@link ImportStatus#REJECTED}), not an HTTP error. For a rejected file the counts
 * cover the rows that could be read.
 *
 * @param batchId the new import batch, or {@code null} unless {@code status} is {@code IMPORTED}
 * @param rowCount data rows in the file (the header excluded)
 * @param saleCount sales only: distinct receipts, i.e. (store_code, receipt_number) pairs, among well-formed rows
 * @param lineCount sales only: line items among well-formed rows
 * @param totalAmount sales only: sum of quantity x unit_price over well-formed rows
 * @param errors the first {@value com.oussamaksantini.insightstudio.importing.ImportService#MAX_REPORTED_ERRORS} errors, by line
 * @param errorCount total number of errors found, which may exceed the errors listed
 * @param created stores or products to create; receipts for sales (= {@code saleCount})
 * @param updated existing stores or products whose values change ({@code create_or_update} only)
 * @param unchanged existing stores or products whose row is identical to what is stored
 * @param categoryChanges products among {@code updated} whose category changes, which moves their
 *     past sales to the new category in category reports
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
        int errorCount,
        ImportKind kind,
        ImportMode mode,
        int created,
        int updated,
        int unchanged,
        int categoryChanges) {
}
