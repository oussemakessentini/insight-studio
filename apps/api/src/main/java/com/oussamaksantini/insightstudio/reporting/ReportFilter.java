package com.oussamaksantini.insightstudio.reporting;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * A validated reporting window. {@code from} and {@code to} are inclusive calendar dates in the
 * business's time zone; {@code storeId} is optional. Create instances with {@link ReportingContext#resolveFilter}.
 */
public record ReportFilter(long businessId, ZoneId zone, LocalDate from, LocalDate to, Long storeId) {

    /** Inclusive start instant, as UTC for the JDBC driver. */
    public OffsetDateTime start() {
        return from.atStartOfDay(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** Exclusive end instant: midnight after {@code to}. */
    public OffsetDateTime endExclusive() {
        return to.plusDays(1).atStartOfDay(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    public DateRange period() {
        return new DateRange(from, to);
    }

    public ReportFilter withPeriod(DateRange period) {
        return new ReportFilter(businessId, zone, period.from(), period.to(), storeId);
    }

    /** The preceding window of equal length, for period-over-period comparisons. */
    public ReportFilter previous() {
        return withPeriod(ReportCalculations.previousPeriod(from, to));
    }
}
