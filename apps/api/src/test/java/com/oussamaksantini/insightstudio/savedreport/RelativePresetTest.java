package com.oussamaksantini.insightstudio.savedreport;

import static org.assertj.core.api.Assertions.assertThat;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import com.oussamaksantini.insightstudio.reporting.ReportingContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** {@link RelativePreset#resolve} is a pure function of the preset and "today". */
class RelativePresetTest {

    private static DateRange range(String from, String to) {
        return new DateRange(LocalDate.parse(from), LocalDate.parse(to));
    }

    private static DateRange resolve(String preset, String today) {
        return RelativePreset.fromCode(preset).orElseThrow().resolve(LocalDate.parse(today));
    }

    @ParameterizedTest(name = "{0} on {1} -> {2}..{3}")
    @CsvSource({
        // N days ending today, inclusive
        "last_7_days,      2026-10-01, 2026-09-25, 2026-10-01",
        "last_30_days,     2026-10-01, 2026-09-02, 2026-10-01",
        "last_90_days,     2026-10-01, 2026-07-04, 2026-10-01",
        "last_365_days,    2026-10-01, 2025-10-02, 2026-10-01",
        "last_7_days,      2026-03-03, 2026-02-25, 2026-03-03",
        "last_7_days,      2028-03-03, 2028-02-26, 2028-03-03",
        "last_30_days,     2026-01-15, 2025-12-17, 2026-01-15",
        "last_365_days,    2028-12-31, 2028-01-02, 2028-12-31",
        // month to date
        "month_to_date,    2026-10-01, 2026-10-01, 2026-10-01",
        "month_to_date,    2026-02-28, 2026-02-01, 2026-02-28",
        "month_to_date,    2028-02-29, 2028-02-01, 2028-02-29",
        // previous calendar month
        "previous_month,   2026-10-01, 2026-09-01, 2026-09-30",
        "previous_month,   2026-01-31, 2025-12-01, 2025-12-31",
        "previous_month,   2026-03-31, 2026-02-01, 2026-02-28",
        "previous_month,   2028-03-01, 2028-02-01, 2028-02-29",
        // whole months before this month
        "last_3_months,    2026-10-01, 2026-07-01, 2026-09-30",
        "last_3_months,    2026-02-15, 2025-11-01, 2026-01-31",
        "last_3_months,    2028-05-31, 2028-02-01, 2028-04-30",
        "last_12_months,   2026-10-01, 2025-10-01, 2026-09-30",
        "last_12_months,   2026-01-01, 2025-01-01, 2025-12-31",
        // quarters
        "quarter_to_date,  2026-10-01, 2026-10-01, 2026-10-01",
        "quarter_to_date,  2026-09-30, 2026-07-01, 2026-09-30",
        "quarter_to_date,  2026-02-14, 2026-01-01, 2026-02-14",
        "quarter_to_date,  2026-06-30, 2026-04-01, 2026-06-30",
        "previous_quarter, 2026-10-01, 2026-07-01, 2026-09-30",
        "previous_quarter, 2026-01-01, 2025-10-01, 2025-12-31",
        "previous_quarter, 2026-05-20, 2026-01-01, 2026-03-31",
        "previous_quarter, 2028-04-01, 2028-01-01, 2028-03-31",
        // years
        "year_to_date,     2026-10-01, 2026-01-01, 2026-10-01",
        "year_to_date,     2026-01-01, 2026-01-01, 2026-01-01",
        "year_to_date,     2028-12-31, 2028-01-01, 2028-12-31",
        "previous_year,    2026-10-01, 2025-01-01, 2025-12-31",
        "previous_year,    2029-01-01, 2028-01-01, 2028-12-31",
    })
    void resolvesAgainstToday(String preset, String today, String from, String to) {
        assertThat(resolve(preset, today)).isEqualTo(range(from, to));
    }

    @ParameterizedTest
    @EnumSource(RelativePreset.class)
    void everyPresetIsAValidReportRangeOnAnyDay(RelativePreset preset) {
        LocalDate end = LocalDate.parse("2029-01-10");
        for (LocalDate day = LocalDate.parse("2027-12-25"); day.isBefore(end); day = day.plusDays(1)) {
            DateRange period = preset.resolve(day);
            assertThat(period.from()).as("%s on %s", preset, day).isBeforeOrEqualTo(period.to());
            assertThat(period.to()).isBeforeOrEqualTo(day);
            assertThat(ChronoUnit.DAYS.between(period.from(), period.to()) + 1)
                    .isLessThanOrEqualTo(ReportingContext.MAX_RANGE_DAYS);
        }
    }

    @Test
    void codesAndLabelsMatchTheContract() {
        assertThat(RelativePreset.values()).extracting(RelativePreset::code).containsExactly(
                "last_7_days", "last_30_days", "last_90_days", "last_365_days", "month_to_date", "previous_month",
                "last_3_months", "last_12_months", "quarter_to_date", "previous_quarter", "year_to_date", "previous_year");
        assertThat(RelativePreset.fromCode("Last_7_days")).isEmpty();
        assertThat(RelativePreset.fromCode(null)).isEmpty();
        assertThat(SavedRange.relative(RelativePreset.PREVIOUS_QUARTER).description())
                .isEqualTo("Previous quarter (relative)");
        assertThat(SavedRange.fixed(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31")).description())
                .isEqualTo("Fixed dates");
    }
}
