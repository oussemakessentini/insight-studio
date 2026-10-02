package com.oussamaksantini.insightstudio.importing;

import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.importing.dto.ImportDetailResponse;
import com.oussamaksantini.insightstudio.importing.dto.ImportListResponse;
import com.oussamaksantini.insightstudio.importing.dto.ImportPreviewResponse;
import com.oussamaksantini.insightstudio.importing.dto.ImportResult;
import com.oussamaksantini.insightstudio.report.ReportFiles;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * CSV imports of sales, stores and products into the current business
 * (docs/catalog-imports-contract.md §4). Every endpoint, including the templates and the batch
 * history, requires the ADMIN or OWNER role with a verified email address; viewers get a 403 and
 * the public demo never reaches it. {@code {kind}} is {@code sales}, {@code stores} or
 * {@code products}; anything else is a 404.
 */
@RestController
@RequestMapping("/api/imports")
class ImportController {

    private final ImportService imports;

    ImportController(ImportService imports) {
        this.imports = imports;
    }

    /**
     * Validates a sales CSV file with the template's header and, when {@code dryRun=false} and there
     * are no errors, imports it. Validation errors are returned with status {@code REJECTED} and HTTP 200.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportResult upload(@RequestPart("file") MultipartFile file, @RequestParam(defaultValue = "true") boolean dryRun) {
        return imports.importFile(file.getOriginalFilename(), bytes(file), dryRun);
    }

    @GetMapping("/templates/{kind}.csv")
    ResponseEntity<String> template(@PathVariable String kind) {
        ImportKind importKind = kind(kind);
        return ReportFiles.csv(importKind.param() + "-template.csv", imports.template(importKind));
    }

    /** The file's columns, first rows and a suggested mapping; nothing is validated or written. */
    @PostMapping(path = "/{kind}/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportPreviewResponse preview(@PathVariable String kind, @RequestPart("file") MultipartFile file) {
        return imports.preview(kind(kind), file.getOriginalFilename(), bytes(file));
    }

    /**
     * Validates a file of {@code kind} with an optional column mapping and, when {@code dryRun=false}
     * and there are no errors, imports it. {@code mapping} and {@code mode} may be multipart parts or
     * request parameters.
     */
    @PostMapping(path = "/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportResult importKind(
            @PathVariable String kind,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String mapping,
            @RequestParam(required = false) String mode,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        ImportKind importKind = kind(kind);
        return imports.importFile(importKind, file.getOriginalFilename(), bytes(file), mapping, mode, dryRun);
    }

    /** The errors of a dry run of the same request, next to the rows they concern; never writes. */
    @PostMapping(path = "/{kind}/errors.csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<String> errors(
            @PathVariable String kind,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String mapping,
            @RequestParam(required = false) String mode) {
        ImportKind importKind = kind(kind);
        String csv = imports.errorsCsv(importKind, file.getOriginalFilename(), bytes(file), mapping, mode);
        return ReportFiles.csv(importKind.param() + "-errors.csv", csv);
    }

    /** History of every import attempt, newest first; {@code kind} keeps one kind. */
    @GetMapping
    ImportListResponse list(
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String kind) {
        ImportKind filter = null;
        if (kind != null && !kind.isBlank()) {
            filter = ImportKind.fromParam(kind.strip()).orElseThrow(() -> ApiException.badRequest(
                    "Invalid value '%s' for parameter 'kind'. Expected one of: sales, stores, products.".formatted(kind)));
        }
        return imports.list(page, size, filter);
    }

    @GetMapping("/{batchId}")
    ImportDetailResponse detail(@PathVariable @Positive long batchId) {
        return imports.detail(batchId);
    }

    private static ImportKind kind(String kind) {
        return ImportKind.fromParam(kind)
                .orElseThrow(() -> ApiException.notFound("There is no '%s' import; use sales, stores or products.".formatted(kind)));
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("The uploaded file could not be read.");
        }
    }
}
