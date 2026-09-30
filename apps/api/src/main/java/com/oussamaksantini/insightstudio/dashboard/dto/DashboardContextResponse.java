package com.oussamaksantini.insightstudio.dashboard.dto;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.util.List;

/**
 * Static context the dashboard needs before querying metrics.
 *
 * @param dataRange first and last dates with sales, or {@code null} when there are no sales yet
 * @param access what the caller may do in this business, so the UI can hide what isn't allowed
 *     (the server enforces the same rules)
 */
public record DashboardContextResponse(
        BusinessInfo business, List<StoreOption> stores, DateRange dataRange, Access access) {

    public record BusinessInfo(String name, String slug, String currency, String timeZone) {
    }

    /**
     * @param role {@code OWNER}, {@code ADMIN}, {@code VIEWER}, or {@code DEMO} for the anonymous public demo
     * @param canImport CSV import and import history (ADMIN+)
     * @param canManageCatalog create stores and products (ADMIN+)
     * @param canManageMembers list and add members (ADMIN+; role changes need OWNER)
     * @param readOnly nothing can be changed (VIEWER or public demo)
     */
    public record Access(
            String role, boolean canImport, boolean canManageCatalog, boolean canManageMembers, boolean readOnly) {
    }
}
