package com.oussamaksantini.insightstudio.importing;

import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.importing.dto.ImportDetailResponse;
import com.oussamaksantini.insightstudio.importing.dto.ImportListResponse;
import com.oussamaksantini.insightstudio.importing.dto.ImportResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * CSV import of historical sales into the current business. Every endpoint, including the batch
 * history, requires the ADMIN or OWNER role; viewers get a 403 and the public demo never reaches it.
 */
@RestController
@RequestMapping("/api/imports")
class ImportController {

    private final ImportService imports;

    ImportController(ImportService imports) {
        this.imports = imports;
    }

    /**
     * Validates a CSV file and, when {@code dryRun=false} and there are no errors, imports it.
     * Validation errors are returned with status {@code REJECTED} and HTTP 200.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportResult upload(@RequestPart("file") MultipartFile file, @RequestParam(defaultValue = "true") boolean dryRun) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("The uploaded file could not be read.");
        }
        return imports.importFile(file.getOriginalFilename(), content, dryRun);
    }

    @GetMapping
    ImportListResponse list(
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return imports.list(page, size);
    }

    @GetMapping("/{batchId}")
    ImportDetailResponse detail(@PathVariable @Positive long batchId) {
        return imports.detail(batchId);
    }
}
