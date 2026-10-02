package com.oussamaksantini.insightstudio.importing;

import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.importing.CatalogValidator.Plan;
import com.oussamaksantini.insightstudio.importing.CatalogValidator.ProductRow;
import com.oussamaksantini.insightstudio.importing.CatalogValidator.StoreRow;
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
import com.oussamaksantini.insightstudio.importing.dto.ImportPreviewResponse;
import com.oussamaksantini.insightstudio.importing.dto.ImportResult;
import com.oussamaksantini.insightstudio.reporting.ReportingContext;
import com.oussamaksantini.insightstudio.tenancy.CurrentBusiness;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates CSV files of sales, stores or products and imports them all-or-nothing into the current
 * business (docs/catalog-imports-contract.md). One pipeline for every kind: read the file, check
 * and apply the column mapping, validate the rows for the kind, then write in one transaction.
 * A dry run, a preview and an errors file never write anything; every real attempt is recorded in
 * the import history, a rejected one without any of its data. Every public method requires the
 * ADMIN role (or OWNER) in the current business, with a verified email address.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Data rows accepted in one sales file (the header excluded). */
    public static final int MAX_ROWS = 50_000;
    /** Data rows accepted in one stores or products file. */
    public static final int MAX_CATALOG_ROWS = 5_000;
    /** Errors listed in a result; {@code errorCount} still reports all of them. */
    public static final int MAX_REPORTED_ERRORS = 100;
    /** Data rows shown by a preview. */
    static final int PREVIEW_ROWS = 10;
    static final int MAX_FILE_NAME_LENGTH = 255;

    static final String CHANGED_WHILE_IMPORTING = "The data changed while importing; try again. Nothing was imported.";
    static final String IMPORT_FAILED = "The import could not be completed; try again. Nothing was imported.";

    private final ReportingContext reporting;
    private final CurrentBusiness current;
    private final ImportQueries queries;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    ImportService(ReportingContext reporting, CurrentBusiness current, ImportQueries queries, TransactionTemplate transaction) {
        this.reporting = reporting;
        this.current = current;
        this.queries = queries;
        this.transaction = transaction;
        this.readOnly = new TransactionTemplate(transaction.getTransactionManager());
        this.readOnly.setReadOnly(true);
    }

    /** A file read into its header (trimmed cells) and data rows. */
    record ParsedFile(List<String> header, List<CsvRecord> rows) {
    }

    /** One upload, checked as far as possible without the database. */
    private record Upload(Business business, Long userId, ImportKind kind, ImportMode mode, String fileName,
            ParsedFile file, String sha256, Map<String, String> mapping) {

        int rowCount() {
            return file.rows().size();
        }
    }

    /** What a file adds up to; for a rejected file, over the rows that could be read. */
    private record Counts(int saleCount, int lineCount, BigDecimal totalAmount, int created, int updated, int unchanged,
            int categoryChanges) {

        static final Counts NONE = new Counts(0, 0, BigDecimal.ZERO.setScale(2), 0, 0, 0, 0);

        static Counts sales(Validation validation) {
            int receipts = validation.receipts().size();
            return new Counts(receipts, validation.lineCount(), validation.totalAmount(), receipts, 0, 0, 0);
        }

        static Counts catalog(Plan<?> plan) {
            return new Counts(0, 0, NONE.totalAmount(), plan.creates().size(), plan.updates().size(), plan.unchanged(),
                    plan.categoryChanges());
        }
    }

    /**
     * The outcome of validating an upload: its result, every error (the result lists at most
     * {@value #MAX_REPORTED_ERRORS}), and when it is {@code VALIDATED}, what to write.
     */
    private record Checked(ImportResult result, Counts counts, List<ImportError> errors, List<PlannedReceipt> receipts,
            Plan<StoreRow> stores, Plan<ProductRow> products) {
    }

    // ---------------------------------------------------------------- imports

    /**
     * {@code POST /api/imports}, unchanged: a sales file whose header is exactly the template's, then
     * the same as {@code POST /api/imports/sales} without a mapping.
     *
     * @throws ApiException 400 when the upload is not a readable CSV file with the expected header
     */
    public ImportResult importFile(String originalFileName, byte[] content, boolean dryRun) {
        Business business = reporting.currentBusiness(Role.ADMIN);
        return importSales(business, current.require(Role.ADMIN).userId(), originalFileName, content, dryRun);
    }

    /**
     * Validates a sales file with the legacy header and, unless {@code dryRun}, imports it into
     * {@code business} (tests use it to act on another business).
     */
    ImportResult importFile(Business business, String originalFileName, byte[] content, boolean dryRun) {
        return importSales(business, null, originalFileName, content, dryRun);
    }

    private ImportResult importSales(Business business, Long userId, String originalFileName, byte[] content, boolean dryRun) {
        String fileName = cleanFileName(originalFileName);
        readRows(content);
        return run(new Upload(business, userId, ImportKind.SALES, ImportMode.CREATE_ONLY, fileName, readFile(content),
                sha256(content), null), dryRun);
    }

    /**
     * Validates a file of {@code kind} and, unless {@code dryRun} or there is any error, imports it.
     *
     * @param mappingJson field to column header as a JSON object, or {@code null} for the identity mapping
     * @param mode {@code create_only} (the default) or {@code create_or_update}
     * @throws ApiException 400 for an unreadable file or a malformed mapping or mode
     */
    public ImportResult importFile(ImportKind kind, String originalFileName, byte[] content, String mappingJson,
            String mode, boolean dryRun) {
        return run(upload(kind, originalFileName, content, mappingJson, mode), dryRun);
    }

    /** The first rows of a file and a suggested mapping for {@code kind}. */
    public ImportPreviewResponse preview(ImportKind kind, String originalFileName, byte[] content) {
        reporting.currentBusiness(Role.ADMIN);
        String fileName = cleanFileName(originalFileName);
        ParsedFile file = readFile(content);
        List<List<String>> sample = file.rows().stream().limit(PREVIEW_ROWS).map(CsvRecord::fields).toList();
        List<ImportPreviewResponse.Field> fields = kind.fields().stream()
                .map(f -> new ImportPreviewResponse.Field(f.name(), f.label(), f.required(), f.description()))
                .toList();
        return new ImportPreviewResponse(kind, fileName, file.rows().size(), file.header(), sample, fields,
                Collections.unmodifiableMap(ColumnMapping.suggest(kind, file.header())));
    }

    /** Every error of a dry run of the same request, as a CSV file with the rows they concern. */
    public String errorsCsv(ImportKind kind, String originalFileName, byte[] content, String mappingJson, String mode) {
        Upload upload = upload(kind, originalFileName, content, mappingJson, mode);
        Checked checked = readOnly.execute(status -> validate(upload, true));
        return ImportFiles.errors(upload.file(), checked.errors());
    }

    /** The template of {@code kind}: its header and a few example rows. */
    public String template(ImportKind kind) {
        reporting.currentBusiness(Role.ADMIN);
        return ImportFiles.template(kind);
    }

    private Upload upload(ImportKind kind, String originalFileName, byte[] content, String mappingJson, String mode) {
        Business business = reporting.currentBusiness(Role.ADMIN);
        Long userId = current.require(Role.ADMIN).userId();
        ImportMode importMode = ImportMode.parse(mode, kind);
        Map<String, String> mapping = parseMapping(mappingJson);
        String fileName = cleanFileName(originalFileName);
        return new Upload(business, userId, kind, importMode, fileName, readFile(content), sha256(content), mapping);
    }

    private ImportResult run(Upload upload, boolean dryRun) {
        if (dryRun) {
            // Read-only transaction: a dry run can never write, whatever happens below.
            return readOnly.execute(status -> validate(upload, true).result());
        }
        ImportResult result;
        try {
            result = transaction.execute(status -> {
                // One import per business at a time, so two uploads can't both pass the duplicate checks.
                queries.lockImports(upload.business().getId());
                Checked checked = validate(upload, false);
                if (checked.result().status() != ImportStatus.VALIDATED) {
                    return checked.result();
                }
                return write(upload, checked);
            });
        } catch (DataIntegrityViolationException e) {
            // Only possible if the data was written by something other than an import meanwhile.
            log.warn("Import of '{}' ({}) into business {} hit a constraint; nothing was imported: {}",
                    upload.fileName(), upload.kind().param(), upload.business().getId(), e.getMostSpecificCause().getMessage());
            result = failed(upload, CHANGED_WHILE_IMPORTING);
        } catch (DataAccessException e) {
            log.error("Import of '{}' ({}) into business {} failed; nothing was imported.",
                    upload.fileName(), upload.kind().param(), upload.business().getId(), e);
            result = failed(upload, IMPORT_FAILED);
        }
        if (result.status() == ImportStatus.REJECTED) {
            // After the rollback, in a transaction of its own: the history keeps the attempt, never its data.
            int errorCount = result.errorCount();
            transaction.executeWithoutResult(status -> queries.insertBatch(new NewBatch(upload.business().getId(),
                    upload.kind(), upload.mode(), ImportStatus.REJECTED, upload.fileName(), upload.sha256(),
                    upload.rowCount(), 0, 0, Counts.NONE.totalAmount(), 0, 0, 0, errorCount, upload.userId())));
        }
        return result;
    }

    private ImportResult write(Upload upload, Checked checked) {
        long businessId = upload.business().getId();
        Counts counts = checked.counts();
        long batchId = queries.insertBatch(new NewBatch(businessId, upload.kind(), upload.mode(), ImportStatus.IMPORTED,
                upload.fileName(), upload.sha256(), upload.rowCount(), counts.saleCount(), counts.lineCount(),
                counts.totalAmount(), counts.created(), counts.updated(), counts.unchanged(), 0, upload.userId()));
        switch (upload.kind()) {
            case SALES -> queries.insertReceipts(batchId, checked.receipts());
            case STORES -> {
                queries.insertStores(businessId, checked.stores().creates());
                queries.updateStores(businessId, checked.stores().updates());
            }
            case PRODUCTS -> {
                queries.insertProducts(businessId, checked.products().creates());
                queries.updateProducts(businessId, checked.products().updates());
            }
        }
        log.info("Imported '{}' ({}, {}) into business {}: batch {}, {} created, {} updated, {} unchanged, {} line items, total {}.",
                upload.fileName(), upload.kind().param(), upload.mode().param(), businessId, batchId, counts.created(),
                counts.updated(), counts.unchanged(), counts.lineCount(), counts.totalAmount());
        return result(upload, batchId, ImportStatus.IMPORTED, false, counts, List.of());
    }

    // ---------------------------------------------------------------- validation

    private Checked validate(Upload upload, boolean dryRun) {
        ImportKind kind = upload.kind();
        List<CsvRecord> rows = upload.file().rows();
        if (rows.size() > kind.maxRows()) {
            String message = "The file has more than %,d data rows; split it into smaller files.".formatted(kind.maxRows());
            // Sales point at the first row too many, as they always have; stores and products reject the file.
            ImportError error = kind == ImportKind.SALES
                    ? ImportError.row(rows.get(kind.maxRows()).line(), message)
                    : ImportError.file(message);
            return rejected(upload, dryRun, Counts.NONE, List.of(error));
        }

        ColumnMapping.Resolution resolution = ColumnMapping.resolve(kind, upload.file().header(), upload.mapping());
        if (!resolution.errors().isEmpty()) {
            return rejected(upload, dryRun, Counts.NONE, resolution.errors());
        }
        ColumnMapping mapping = resolution.mapping();
        List<ImportError> errors = new ArrayList<>();
        List<CsvRecord> mapped = mapping.apply(rows, errors);

        long businessId = upload.business().getId();
        Counts counts;
        List<PlannedReceipt> receipts = List.of();
        Plan<StoreRow> stores = null;
        Plan<ProductRow> products = null;
        switch (kind) {
            case SALES -> {
                Catalog catalog = new Catalog(queries.storeIdsByCode(businessId), queries.productIdsBySku(businessId),
                        upload.business().zoneId());
                Validation validation = ImportValidator.validate(mapped, catalog, queries::existingReceipts);
                errors.addAll(validation.errors());
                receipts = validation.receipts();
                counts = Counts.sales(validation);
            }
            case STORES -> {
                stores = CatalogValidator.stores(mapped, mapping.isMapped(CatalogValidator.CITY), upload.mode(),
                        queries.storesByCode(businessId));
                errors.addAll(stores.errors());
                counts = Counts.catalog(stores);
            }
            case PRODUCTS -> {
                products = CatalogValidator.products(mapped, upload.mode(), queries.productsBySku(businessId));
                errors.addAll(products.errors());
                counts = Counts.catalog(products);
            }
            default -> throw new IllegalStateException("Unknown import kind " + kind);
        }

        Optional<Instant> importedAt = queries.importedAt(businessId, kind, upload.sha256());
        if (importedAt.isPresent()) {
            // Every row would be reported as existing too; the file-level reason is the useful one.
            String date = importedAt.get().atZone(upload.business().zoneId()).toLocalDate().toString();
            ImportError error = ImportError.file(
                    "This file was already imported on %s (same content). Nothing was imported.".formatted(date));
            return rejected(upload, dryRun, counts, List.of(error));
        }
        if (!errors.isEmpty()) {
            List<String> fields = kind.fieldNames();
            List<ImportError> located = errors.stream()
                    .sorted(Comparator.comparing(ImportError::line, Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(e -> e.field() == null ? -1 : fields.indexOf(e.field())))
                    .map(mapping::withColumn)
                    .toList();
            return rejected(upload, dryRun, counts, located);
        }
        ImportResult result = result(upload, null, ImportStatus.VALIDATED, dryRun, counts, List.of());
        return new Checked(result, counts, List.of(), receipts, stores, products);
    }

    private static Checked rejected(Upload upload, boolean dryRun, Counts counts, List<ImportError> errors) {
        ImportResult result = result(upload, null, ImportStatus.REJECTED, dryRun, counts, errors);
        return new Checked(result, counts, errors, List.of(), null, null);
    }

    /** A real import that failed while writing: nothing was written, so no counts. */
    private static ImportResult failed(Upload upload, String message) {
        return result(upload, null, ImportStatus.REJECTED, false, Counts.NONE, List.of(ImportError.file(message)));
    }

    private static ImportResult result(Upload upload, Long batchId, ImportStatus status, boolean dryRun, Counts counts,
            List<ImportError> errors) {
        List<ImportError> reported = errors.size() > MAX_REPORTED_ERRORS ? errors.subList(0, MAX_REPORTED_ERRORS) : errors;
        return new ImportResult(batchId, status, dryRun, upload.fileName(), upload.rowCount(), counts.saleCount(),
                counts.lineCount(), counts.totalAmount(), List.copyOf(reported), errors.size(), upload.kind(),
                upload.mode(), counts.created(), counts.updated(), counts.unchanged(), counts.categoryChanges());
    }

    // ---------------------------------------------------------------- history

    /** @param kind only imports of this kind, or every import when {@code null} */
    public ImportListResponse list(int page, int size, ImportKind kind) {
        long businessId = reporting.currentBusiness(Role.ADMIN).getId();
        return readOnly.execute(status -> {
            long total = queries.countBatches(businessId, kind);
            long offset = (long) page * size;
            List<ImportBatchSummary> items = offset < total ? queries.pageBatches(businessId, kind, size, offset) : List.of();
            int totalPages = (int) ((total + size - 1) / size);
            return new ImportListResponse(page, size, total, totalPages, items);
        });
    }

    public ImportDetailResponse detail(long batchId) {
        long businessId = reporting.currentBusiness(Role.ADMIN).getId();
        return readOnly.execute(status -> queries.detail(batchId, businessId))
                .orElseThrow(() -> ApiException.notFound("Import %d was not found.".formatted(batchId)));
    }

    // ---------------------------------------------------------------- reading the upload

    /**
     * {@code mapping} as sent by the client: a JSON object of field names to column headers (or
     * {@code null}). Problems with what it maps are reported by the import itself, as file errors.
     *
     * @return the mapping, or {@code null} when absent (the identity mapping)
     * @throws ApiException 400 when it is not such a JSON object
     */
    static Map<String, String> parseMapping(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        String format = "'mapping' must be a JSON object of field names to column headers (or null), "
                + "e.g. {\"code\": \"Store ID\", \"city\": null}.";
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (JacksonException e) {
            throw ApiException.badRequest(format);
        }
        if (node == null || !node.isObject()) {
            throw ApiException.badRequest(format);
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            JsonNode value = entry.getValue();
            if (value.isNull()) {
                mapping.put(entry.getKey(), null);
            } else if (value.isString()) {
                mapping.put(entry.getKey(), value.asString());
            } else {
                throw ApiException.badRequest(format);
            }
        }
        return mapping;
    }

    /**
     * Decodes and parses an upload of any kind: its header (trimmed cells, any names) and data rows.
     *
     * @throws ApiException 400 for an empty, binary or non-UTF-8 file, broken quoting, no header or no data rows
     */
    static ParsedFile readFile(byte[] content) {
        List<CsvRecord> records = parse(content);
        CsvRecord header = records.getFirst();
        if (header.line() != 1 || header.fields().stream().allMatch(String::isBlank)) {
            throw ApiException.badRequest("The first line must be the header, with the name of each column.");
        }
        if (records.size() == 1) {
            throw ApiException.badRequest("The file has a header but no data rows.");
        }
        return new ParsedFile(header.fields().stream().map(String::strip).toList(), records.subList(1, records.size()));
    }

    /**
     * Decodes and parses a legacy sales upload, returning its data rows (header removed).
     *
     * @throws ApiException 400 for an empty, binary or non-UTF-8 file, broken quoting or a wrong header
     */
    static List<CsvRecord> readRows(byte[] content) {
        List<CsvRecord> records = parse(content);
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

    private static List<CsvRecord> parse(byte[] content) {
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
        return records;
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
