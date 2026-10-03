package com.oussamaksantini.insightstudio.business.dto;

import com.oussamaksantini.insightstudio.tenancy.Role;
import java.time.Instant;

/**
 * {@code GET /api/businesses/{id}/settings} (docs/account-management-contract.md §1).
 *
 * @param currencyChangeAllowed whether the business holds no amounts yet (no products, no sales), so an
 *     OWNER may still change its currency
 * @param currencyLockedReason why the currency can no longer change, or {@code null}
 */
public record BusinessSettingsResponse(
        long id,
        String name,
        String slug,
        String currency,
        String timeZone,
        Role role,
        boolean currencyChangeAllowed,
        String currencyLockedReason,
        Instant createdAt) {
}
