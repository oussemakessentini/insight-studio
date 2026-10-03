package com.oussamaksantini.insightstudio.business.dto;

import java.util.List;

/**
 * {@code GET /api/businesses/{id}/time-zone-preview} (docs/account-management-contract.md §1): what a
 * change from {@code from} to {@code to} does to the reports. Nothing is stored differently; only the
 * local day and month of each sale change.
 *
 * @param salesTotal orders (receipts with at least one item)
 * @param months only the months whose totals differ, newest first, at most 24
 */
public record TimeZonePreviewResponse(
        String from, String to, long salesTotal, long salesChangingDay, long salesChangingMonth, List<Month> months) {

    /** Amounts are strings with two decimals, like the reports. */
    public record Month(String month, String revenueBefore, String revenueAfter, long ordersBefore, long ordersAfter) {
    }
}
