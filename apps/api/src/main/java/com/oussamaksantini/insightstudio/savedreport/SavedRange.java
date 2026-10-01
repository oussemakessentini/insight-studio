package com.oussamaksantini.insightstudio.savedreport;

import java.time.ZoneId;

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

    /**
     * For exports: "Previous quarter (rolling: from today's date in America/New_York)", or "Fixed
     * dates". A rolling range is recalculated from the current date in the business's time zone
     * every time the report runs, and the description says so.
     */
    public String description(ZoneId businessZone) {
        return isRelative()
                ? "%s (rolling: from today's date in %s)".formatted(preset.label(), businessZone.getId())
                : "Fixed dates";
    }
}
