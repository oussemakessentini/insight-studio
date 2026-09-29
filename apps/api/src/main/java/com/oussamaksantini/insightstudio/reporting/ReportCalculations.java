package com.oussamaksantini.insightstudio.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Pure calculation helpers for reporting metrics. All money values use scale 2, percentages scale 1. */
public final class ReportCalculations {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ReportCalculations() {
    }

    public static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal averageOrderValue(BigDecimal revenue, long orders) {
        return average(revenue, orders);
    }

    /** {@code total / count} as money, or zero when {@code count} is zero. */
    public static BigDecimal average(BigDecimal total, long count) {
        if (count == 0) {
            return money(BigDecimal.ZERO);
        }
        return total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    /** Percentage change from {@code previous} to {@code current}, or {@code null} when either is missing or there is no baseline. */
    public static BigDecimal percentChange(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() == 0) {
            return null;
        }
        return current.subtract(previous)
                .multiply(HUNDRED)
                .divide(previous.abs(), 1, RoundingMode.HALF_UP);
    }

    /** {@code part} as a percentage of {@code total}; zero when the total is zero. */
    public static BigDecimal sharePercent(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return part.multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP);
    }

    /** The window of equal length immediately preceding {@code [from, to]}. */
    public static DateRange previousPeriod(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        return new DateRange(from.minusDays(days), from.minusDays(1));
    }

    /** Number of calendar days in the bucket starting at {@code bucketStart}. */
    public static int bucketDays(LocalDate bucketStart, Granularity granularity) {
        return (int) ChronoUnit.DAYS.between(bucketStart, granularity.next(bucketStart));
    }

    /** Days of the bucket starting at {@code bucketStart} that fall within {@code [from, to]}. */
    public static int daysCovered(LocalDate bucketStart, Granularity granularity, LocalDate from, LocalDate to) {
        LocalDate bucketEnd = granularity.next(bucketStart).minusDays(1);
        LocalDate start = bucketStart.isBefore(from) ? from : bucketStart;
        LocalDate end = bucketEnd.isAfter(to) ? to : bucketEnd;
        return end.isBefore(start) ? 0 : (int) ChronoUnit.DAYS.between(start, end) + 1;
    }

    /**
     * One bucket of a time series.
     *
     * @param daysCovered days of the bucket inside the requested range
     * @param bucketDays total days in the bucket
     */
    public record Bucket(LocalDate start, int daysCovered, int bucketDays) {

        public boolean complete() {
            return daysCovered == bucketDays;
        }
    }

    /** Every bucket overlapping {@code [from, to]}, in order, with how much of each lies inside the range. */
    public static List<Bucket> buckets(LocalDate from, LocalDate to, Granularity granularity) {
        return bucketStarts(from, to, granularity).stream()
                .map(start -> new Bucket(start, daysCovered(start, granularity, from, to), bucketDays(start, granularity)))
                .toList();
    }

    /** Start dates of every bucket that overlaps {@code [from, to]}, in order. */
    public static List<LocalDate> bucketStarts(LocalDate from, LocalDate to, Granularity granularity) {
        List<LocalDate> starts = new ArrayList<>();
        LocalDate last = granularity.truncate(to);
        for (LocalDate d = granularity.truncate(from); !d.isAfter(last); d = granularity.next(d)) {
            starts.add(d);
        }
        return starts;
    }
}
