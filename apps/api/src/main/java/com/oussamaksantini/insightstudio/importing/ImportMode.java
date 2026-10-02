package com.oussamaksantini.insightstudio.importing;

import com.fasterxml.jackson.annotation.JsonValue;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import java.util.Locale;

/** What an import does with a row whose key already exists (docs/catalog-imports-contract.md §2). */
public enum ImportMode {
    /** New keys are created; an existing key is a row error. The only mode for sales. */
    CREATE_ONLY,
    /** New keys are created; existing stores and products are updated (identical rows count as unchanged). */
    CREATE_OR_UPDATE;

    /** {@code create_only} or {@code create_or_update}, as in requests, JSON and {@code import_batches.mode}. */
    @JsonValue
    public String param() {
        return name().toLowerCase(Locale.ROOT);
    }

    static ImportMode fromParam(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /**
     * The mode requested for {@code kind}: {@link #CREATE_ONLY} when absent.
     *
     * @throws ApiException 400 for an unknown mode, or any mode but {@code create_only} for sales
     */
    static ImportMode parse(String value, ImportKind kind) {
        if (value == null || value.isBlank()) {
            return CREATE_ONLY;
        }
        String clean = value.strip();
        if (CREATE_ONLY.param().equals(clean)) {
            return CREATE_ONLY;
        }
        if (CREATE_OR_UPDATE.param().equals(clean)) {
            if (kind == ImportKind.SALES) {
                throw ApiException.badRequest("Sales imports only add receipts; 'mode' must be create_only.");
            }
            return CREATE_OR_UPDATE;
        }
        throw ApiException.badRequest("'mode' must be create_only or create_or_update (got '%s').".formatted(clean));
    }
}
