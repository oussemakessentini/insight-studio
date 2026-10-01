package com.oussamaksantini.insightstudio.report.pdf;

import com.oussamaksantini.insightstudio.business.Business;
import java.time.ZoneId;

/**
 * Everything a report PDF shows besides the report itself. Only labels: every figure comes from the
 * report response.
 *
 * @param title the saved report's name, or "Monthly report" / "Category report"
 * @param storeLabel "All stores", or the store's name and code
 * @param rangeDescription "Previous quarter (relative)" or "Fixed dates" for a saved report;
 *     {@code null} for an ad-hoc report
 */
public record ReportPdfDetails(
        String title,
        String businessName,
        String currency,
        ZoneId zone,
        String storeLabel,
        String rangeDescription) {

    public static final String ALL_STORES = "All stores";

    public static ReportPdfDetails of(String title, Business business, String storeLabel, String rangeDescription) {
        return new ReportPdfDetails(
                title, business.getName(), business.getCurrency(), business.zoneId(), storeLabel, rangeDescription);
    }

    /** "Boston (BOS)", or "All stores" without a store. */
    public static String storeLabel(String name, String code) {
        if (name == null) {
            return ALL_STORES;
        }
        return code == null || code.isBlank() ? name : "%s (%s)".formatted(name, code);
    }
}
