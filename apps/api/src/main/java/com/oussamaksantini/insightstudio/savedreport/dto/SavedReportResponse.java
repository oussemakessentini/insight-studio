package com.oussamaksantini.insightstudio.savedreport.dto;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A saved report definition (docs/saved-reports-contract.md §3).
 *
 * @param storeName the store's name, or {@code null} for all stores
 * @param period the range resolved now, in the business's time zone
 * @param createdBy display name of the member who created the definition
 */
public record SavedReportResponse(
        long id,
        String name,
        String kind,
        Range range,
        Long storeId,
        String storeName,
        DateRange period,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /** {@code from}/{@code to} are set for {@code fixed}, {@code preset} for {@code relative}; the others are {@code null}. */
    public record Range(String type, String preset, LocalDate from, LocalDate to) {
    }
}
