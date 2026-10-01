package com.oussamaksantini.insightstudio.savedreport.dto;

/**
 * {@code POST /api/saved-reports} and {@code PUT /api/saved-reports/{id}}. Everything is checked by
 * the service, so wrong values get one clear 400 each.
 *
 * @param kind {@code monthly} or {@code categories}
 * @param storeId a store of the current business, or {@code null} for all stores
 */
public record SavedReportRequest(String name, String kind, Range range, Long storeId) {

    /**
     * @param type {@code fixed} (with {@code from} and {@code to}, ISO dates) or {@code relative}
     *     (with {@code preset}); the fields of the other type are ignored
     */
    public record Range(String type, String preset, String from, String to) {
    }
}
