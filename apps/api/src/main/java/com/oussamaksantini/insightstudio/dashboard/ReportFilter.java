package com.oussamaksantini.insightstudio.dashboard;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * A validated reporting window. {@code from} and {@code to} are inclusive calendar dates in the
 * business's time zone; {@code storeId} is optional.
 */
record ReportFilter(long businessId, ZoneId zone, LocalDate from, LocalDate to, Long storeId) {

    /** Inclusive start instant, as UTC for the JDBC driver. */
    OffsetDateTime start() {
        return from.atStartOfDay(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** Exclusive end instant: midnight after {@code to}. */
    OffsetDateTime endExclusive() {
        return to.plusDays(1).atStartOfDay(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    ReportFilter withPeriod(LocalDate newFrom, LocalDate newTo) {
        return new ReportFilter(businessId, zone, newFrom, newTo, storeId);
    }
}
