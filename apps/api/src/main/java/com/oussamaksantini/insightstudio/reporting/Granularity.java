package com.oussamaksantini.insightstudio.reporting;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Size of each bucket in the revenue time series. Weeks start on Monday, matching PostgreSQL's date_trunc. */
public enum Granularity {
    DAY,
    WEEK,
    MONTH;

    /** Inclusive range lengths (in days) up to which DAY and WEEK are chosen automatically. */
    static final int AUTO_DAY_MAX_DAYS = 62;
    static final int AUTO_WEEK_MAX_DAYS = 366;

    public static Granularity auto(long rangeDays) {
        if (rangeDays <= AUTO_DAY_MAX_DAYS) {
            return DAY;
        }
        return rangeDays <= AUTO_WEEK_MAX_DAYS ? WEEK : MONTH;
    }

    public static Granularity fromParam(String value) {
        return Arrays.stream(values())
                .filter(g -> g.param().equals(value.trim().toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid value '%s' for parameter 'granularity'. Expected one of: %s.".formatted(
                                value, Arrays.stream(values()).map(Granularity::param).collect(Collectors.joining(", ")))));
    }

    @JsonValue
    public String param() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** PostgreSQL date_trunc field name; safe to inline in SQL because it never comes from user input. */
    public String sqlUnit() {
        return param();
    }

    public LocalDate truncate(LocalDate date) {
        return switch (this) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    public LocalDate next(LocalDate bucketStart) {
        return switch (this) {
            case DAY -> bucketStart.plusDays(1);
            case WEEK -> bucketStart.plusWeeks(1);
            case MONTH -> bucketStart.plusMonths(1);
        };
    }
}
