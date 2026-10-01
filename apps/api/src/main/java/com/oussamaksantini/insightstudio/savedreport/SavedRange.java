package com.oussamaksantini.insightstudio.savedreport;

import java.time.LocalDate;

/**
 * The date range of a saved definition: fixed dates, or a preset resolved at run time. Exactly one
 * of ({@code from}, {@code to}) and {@code preset} is set, as the {@code ck_saved_reports_range}
 * constraint requires.
 */
public record SavedRange(LocalDate from, LocalDate to, RelativePreset preset) {

    public static final String FIXED = "fixed";
    public static final String RELATIVE = "relative";

    public static SavedRange fixed(LocalDate from, LocalDate to) {
        return new SavedRange(from, to, null);
    }

    public static SavedRange relative(RelativePreset preset) {
        return new SavedRange(null, null, preset);
    }

    public boolean isRelative() {
        return preset != null;
    }

    /** {@value #FIXED} or {@value #RELATIVE}, as stored in {@code saved_reports.range_type}. */
    public String type() {
        return isRelative() ? RELATIVE : FIXED;
    }

    /** "Previous quarter (relative)" or "Fixed dates", for exports. */
    public String description() {
        return isRelative() ? preset.label() + " (relative)" : "Fixed dates";
    }
}
