package com.oussamaksantini.insightstudio.importing;

import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.common.ImportsProperties;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.importing.CsvParser.CsvRecord;
import com.oussamaksantini.insightstudio.importing.CsvParser.CsvSyntaxException;
import com.oussamaksantini.insightstudio.importing.ImportQueries.NewBatch;
import com.oussamaksantini.insightstudio.importing.ImportValidator.Catalog;
import com.oussamaksantini.insightstudio.importing.ImportValidator.PlannedReceipt;
import com.oussamaksantini.insightstudio.importing.ImportValidator.Validation;
import com.oussamaksantini.insightstudio.importing.dto.ImportBatchSummary;
import com.oussamaksantini.insightstudio.importing.dto.ImportDetailResponse;
import com.oussamaksantini.insightstudio.importing.dto.ImportError;
import com.oussamaksantini.insightstudio.importing.dto.ImportListResponse;
import com.oussamaksantini.insightstudio.importing.dto.ImportResult;
import com.oussamaksantini.insightstudio.reporting.ReportingContext;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Validates CSV files of historical sales and imports them all-or-nothing into the current
 * business. A dry run and a rejected file never write anything. Exists only when imports are
 * enabled ({@value ImportsProperties#ENABLED_PROPERTY}), which only the {@code local} profile does.
 */
@Service
@ConditionalOnProperty(name = ImportsProperties.ENABLED_PROPERTY, havingValue = "true")
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    /** Data rows accepted in one file (the header excluded). */
    public static final int MAX_ROWS = 50_000;
    /** Errors listed in a result; {@code errorCount} still reports all of them. */
    public static final int MAX_REPORTED_ERRORS = 100;
    static final int MAX_FILE_NAME_LENGTH = 255;

    private final ReportingContext reporting;
    private final ImportQueries queries;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    ImportService(ReportingContext reporting, ImportQueries queries, TransactionTemplate transaction) {
        this.reporting = reporting;
        this.queries = queries;
        this.transaction = transaction;
        this.readOnly = new TransactionTemplate(transaction.getTransactionManager());
        this.readOnly.setReadOnly(true);
    }

    /** Validates {@code content} and, unless {@code dryRun}, imports it into the current business. */
    public ImportResult importFile(String originalFileName, byte[] content, boolean dryRun) {
        return importFile(reporting.currentBusiness(), originalFileName, content, dryRun);
    }

    /**
     * Validates {@code content} and, unless {@code dryRun}, imports it into {@code business}.
     *
     * @throws ApiException 400 when the upload is not a readable CSV file with the expected header
     */
    ImportResult importFile(Business business, String originalFileName, byte[] content, boolean dryRun) {
        String fileName = cleanFileName(originalFileName);
        List<CsvRecord> rows = readRows(content);
        String sha256 = sha256(content);

        if (dryRun) {
            // Read-only transaction: a dry run can never write, whatever happens below.
            return readOnly.execute(status -> validate(business, fileName, rows, sha256, true).result());
        }
        try {
            return transaction.execute(status -> {
                // One import per business at a time, so two uploads can't both pass the duplicate checks.
                queries.lockBusiness(business.getId());
                Checked checked = validate(business, fileName, rows, sha256, false);
                ImportResult result = checked.result();
                if (result.status() != ImportStatus.VALIDATED) {
                    return result;
                }
                long batchId = queries.insertBatch(
                        new NewBatch(business.getId(), fileName, sha256, result.rowCount(), result.saleCount(),
                                result.lineCount(), result.totalAmount()),
                        checked.receipts());
                log.info("Imported '{}' into business {}: batch {}, {} receipts, {} line items, total {}.",
                        fileName, business.getId(), batchId, result.saleCount(), result.lineCount(), result.totalAmount());
                return new ImportResult(batchId, ImportStatus.IMPORTED, false, fileName, result.rowCount(),
                        result.saleCount(), result.lineCount(), result.totalAmount(), List.of(), 0);
            });
        } catch (DuplicateKeyException e) {
            // Only possible if receipts were written by something other than an import meanwhile.
            throw new ApiException(HttpStatus.CONFLICT,
                    "The data changed while importing; nothing was imported. Validate the file again.");
        }
    }

    public ImportListResponse list(int page, int size) {
        long businessId = reporting.currentBusiness().getId();
        return readOnly.execute(status -> {
            long total = queries.countBatches(businessId);
            long offset = (long) page * size;
            List<ImportBatchSummary> items = offset < total ? queries.pageBatches(businessId, size, offset) : List.of();
            int totalPages = (int) ((total + size - 1) / size);
            return new ImportListResponse(page, size, total, totalPages, items);
        });
    }

    public ImportDetailResponse detail(long batchId) {
        long businessId = reporting.currentBusiness().getId();
        return readOnly.execute(status -> queries.detail(batchId, businessId))
                .orElseThrow(() -> ApiException.notFound("Import %d was not found.".formatted(batchId)));
    }

    /** A validation result and, when it is {@code VALIDATED}, the receipts to write. */
    private record Checked(ImportResult result, List<PlannedReceipt> receipts) {

        static Checked rejected(ImportResult result) {
            return new Checked(result, List.of());
        }
    }

    private Checked validate(Business business, String fileName, List<CsvRecord> rows, String sha256, boolean dryRun) {
        if (rows.size() > MAX_ROWS) {
            ImportError error = ImportError.row(rows.get(MAX_ROWS).line(),
                    "The file has more than %,d data rows; split it into smaller files.".formatted(MAX_ROWS));
            return Checked.rejected(rejected(fileName, dryRun, rows.size(), 0, 0, BigDecimal.ZERO.setScale(2), List.of(error)));
        }

        Catalog catalog = new Catalog(
                queries.storeIdsByCode(business.getId()), queries.productIdsBySku(business.getId()), business.zoneId());
        Validation validation = ImportValidator.validate(rows, catalog, queries::existingReceipts);
        int saleCount = validation.receipts().size();

        if (queries.hashExists(business.getId(), sha256)) {
            // Every receipt would be reported as existing too; the file-level reason is the useful one.
            ImportError error = ImportError.file("This file has already been imported (same content). Nothing was imported.");
            return Checked.rejected(rejected(fileName, dryRun, validation.rowCount(), saleCount,
                    validation.lineCount(), validation.totalAmount(), List.of(error)));
        }
        if (!validation.errors().isEmpty()) {
            return Checked.rejected(rejected(fileName, dryRun, validation.rowCount(), saleCount,
                    validation.lineCount(), validation.totalAmount(), validation.errors()));
        }
        ImportResult result = new ImportResult(null, ImportStatus.VALIDATED, dryRun, fileName, validation.rowCount(),
                saleCount, validation.lineCount(), validation.totalAmount(), List.of(), 0);
        return new Checked(result, validation.receipts());
    }

    private static ImportResult rejected(String fileName, boolean dryRun, int rowCount, int saleCount, int lineCount,
            BigDecimal total, List<ImportError> errors) {
        List<ImportError> reported = errors.size() > MAX_REPORTED_ERRORS ? errors.subList(0, MAX_REPORTED_ERRORS) : errors;
        return new ImportResult(null, ImportStatus.REJECTED, dryRun, fileName, rowCount, saleCount, lineCount, total,
                List.copyOf(reported), errors.size());
    }

    /**
     * Decodes and parses the upload, returning its data rows (header removed).
     *
     * @throws ApiException 400 for an empty, binary or non-UTF-8 file, broken quoting or a wrong header
     */
    static List<CsvRecord> readRows(byte[] content) {
        String text = decode(content);
        List<CsvRecord> records;
        try {
            // Header + MAX_ROWS + one more, to detect a file that is too long without parsing all of it.
            records = CsvParser.parse(text, MAX_ROWS + 2);
        } catch (CsvSyntaxException e) {
            throw ApiException.badRequest("The file is not valid CSV: line %d: %s".formatted(e.line(), e.getMessage()));
        }
        if (records.isEmpty()) {
            throw ApiException.badRequest("The file is empty.");
        }
        CsvRecord header = records.getFirst();
        if (header.line() != 1 || !ImportValidator.isValidHeader(header.fields())) {
            throw ApiException.badRequest("The first line must be the header: %s."
                    .formatted(String.join(",", ImportValidator.COLUMNS)));
        }
        if (records.size() == 1) {
            throw ApiException.badRequest("The file has a header but no data rows.");
        }
        return records.subList(1, records.size());
    }

    private static String decode(byte[] content) {
        if (content.length == 0) {
            throw ApiException.badRequest("The file is empty.");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw ApiException.badRequest("The file is not UTF-8 encoded text. Save it as \"CSV UTF-8\" and try again.");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        if (text.indexOf('\0') >= 0) {
            throw ApiException.badRequest("The file is not a CSV text file.");
        }
        return text;
    }

    /**
     * Keeps only the base name (browsers may send a path), and requires a {@code .csv} extension so
     * spreadsheets and other files are turned away with a clear message.
     */
    static String cleanFileName(String original) {
        String name = original == null ? "" : original.strip();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1).strip();
        if (name.isEmpty()) {
            throw ApiException.badRequest("The upload has no file name; choose a .csv file.");
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw ApiException.badRequest("Only .csv files can be imported (got '%s').".formatted(truncate(name)));
        }
        return truncate(name);
    }

    private static String truncate(String name) {
        return name.length() <= MAX_FILE_NAME_LENGTH ? name : name.substring(0, MAX_FILE_NAME_LENGTH);
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
