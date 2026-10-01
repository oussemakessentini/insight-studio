package com.oussamaksantini.insightstudio.savedreport;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

/**
 * A date range relative to "today" (docs/saved-reports-contract.md §2). {@link #resolve} is a pure
 * function of the preset and the date, so it can be tested with any day; the caller supplies today
 * in the business's time zone (see {@link PeriodResolver}).
 *
 * <p>The codes are the values stored in {@code saved_reports.relative_preset} and sent by the API.
 */
public enum RelativePreset {
    LAST_7_DAYS("last_7_days", "Last 7 days"),
    LAST_30_DAYS("last_30_days", "Last 30 days"),
    LAST_90_DAYS("last_90_days", "Last 90 days"),
    LAST_365_DAYS("last_365_days", "Last 365 days"),
    MONTH_TO_DATE("month_to_date", "Month to date"),
    PREVIOUS_MONTH("previous_month", "Previous month"),
    LAST_3_MONTHS("last_3_months", "Last 3 months"),
    LAST_12_MONTHS("last_12_months", "Last 12 months"),
    QUARTER_TO_DATE("quarter_to_date", "Quarter to date"),
    PREVIOUS_QUARTER("previous_quarter", "Previous quarter"),
    YEAR_TO_DATE("year_to_date", "Year to date"),
    PREVIOUS_YEAR("previous_year", "Previous year");

    private final String code;
    private final String label;

    RelativePreset(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    /** Human-readable name, e.g. "Previous quarter". */
    public String label() {
        return label;
    }

    public static Optional<RelativePreset> fromCode(String code) {
        return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst();
    }

    /** The inclusive period this preset covers when the current date is {@code today}. */
    public DateRange resolve(LocalDate today) {
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate quarterStart = today.withMonth(firstMonthOfQuarter(today)).withDayOfMonth(1);
        LocalDate yearStart = today.withDayOfYear(1);
        return switch (this) {
            case LAST_7_DAYS -> lastDays(today, 7);
            case LAST_30_DAYS -> lastDays(today, 30);
            case LAST_90_DAYS -> lastDays(today, 90);
            case LAST_365_DAYS -> lastDays(today, 365);
            case MONTH_TO_DATE -> new DateRange(monthStart, today);
            case PREVIOUS_MONTH -> new DateRange(monthStart.minusMonths(1), monthStart.minusDays(1));
            case LAST_3_MONTHS -> new DateRange(monthStart.minusMonths(3), monthStart.minusDays(1));
            case LAST_12_MONTHS -> new DateRange(monthStart.minusMonths(12), monthStart.minusDays(1));
            case QUARTER_TO_DATE -> new DateRange(quarterStart, today);
            case PREVIOUS_QUARTER -> new DateRange(quarterStart.minusMonths(3), quarterStart.minusDays(1));
            case YEAR_TO_DATE -> new DateRange(yearStart, today);
            case PREVIOUS_YEAR -> new DateRange(yearStart.minusYears(1), yearStart.minusDays(1));
        };
    }

    /** The {@code days} days ending today, today included. */
    private static DateRange lastDays(LocalDate today, int days) {
        return new DateRange(today.minusDays(days - 1L), today);
    }

    private static int firstMonthOfQuarter(LocalDate date) {
        return (date.getMonthValue() - 1) / 3 * 3 + 1;
    }
}
