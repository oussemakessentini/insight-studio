package com.oussamaksantini.insightstudio.savedreport;

import static org.assertj.core.api.Assertions.assertThat;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** "Today" is the date in the business's time zone, read from the injected clock. */
class PeriodResolverTest {

    private static final ZoneId AUCKLAND = ZoneId.of("Pacific/Auckland");
    private static final ZoneId KIRITIMATI = ZoneId.of("Pacific/Kiritimati");
    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

    private static PeriodResolver at(String instant) {
        return new PeriodResolver(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static DateRange range(String from, String to) {
        return new DateRange(LocalDate.parse(from), LocalDate.parse(to));
    }

    @Test
    void todayDependsOnTheBusinessTimeZoneNotOnUtc() {
        // 30 Sep 2026 23:30 UTC: already 1 Oct in Auckland (UTC+13) and Kiritimati (UTC+14), still 30 Sep in LA.
        PeriodResolver resolver = at("2026-09-30T23:30:00Z");
        assertThat(resolver.today(ZoneOffset.UTC)).isEqualTo(LocalDate.parse("2026-09-30"));
        assertThat(resolver.today(AUCKLAND)).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(resolver.today(KIRITIMATI)).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(resolver.today(LOS_ANGELES)).isEqualTo(LocalDate.parse("2026-09-30"));

        SavedRange previousQuarter = SavedRange.relative(RelativePreset.PREVIOUS_QUARTER);
        assertThat(resolver.resolve(previousQuarter, AUCKLAND)).isEqualTo(range("2026-07-01", "2026-09-30"));
        assertThat(resolver.resolve(previousQuarter, LOS_ANGELES)).isEqualTo(range("2026-04-01", "2026-06-30"));
    }

    @Test
    void newYearArrivesAtDifferentInstants() {
        // 31 Dec 2026 10:30 UTC: 1 Jan 2027 00:30 in Kiritimati, 31 Dec 02:30 in Los Angeles.
        PeriodResolver resolver = at("2026-12-31T10:30:00Z");
        SavedRange ytd = SavedRange.relative(RelativePreset.YEAR_TO_DATE);
        assertThat(resolver.resolve(ytd, KIRITIMATI)).isEqualTo(range("2027-01-01", "2027-01-01"));
        assertThat(resolver.resolve(ytd, LOS_ANGELES)).isEqualTo(range("2026-01-01", "2026-12-31"));
        SavedRange last7 = SavedRange.relative(RelativePreset.LAST_7_DAYS);
        assertThat(resolver.resolve(last7, KIRITIMATI)).isEqualTo(range("2026-12-26", "2027-01-01"));
        assertThat(resolver.resolve(last7, LOS_ANGELES)).isEqualTo(range("2026-12-25", "2026-12-31"));
    }

    @Test
    void fixedRangesAreReturnedAsStored() {
        SavedRange fixed = SavedRange.fixed(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-08-31"));
        assertThat(at("2030-01-01T00:00:00Z").resolve(fixed, AUCKLAND)).isEqualTo(range("2026-06-01", "2026-08-31"));
    }
}
