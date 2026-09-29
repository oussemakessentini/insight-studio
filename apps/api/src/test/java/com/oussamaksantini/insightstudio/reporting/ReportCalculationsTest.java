package com.oussamaksantini.insightstudio.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReportCalculationsTest {

    @Test
    void averageOrderValueRoundsHalfUpToCents() {
        assertThat(ReportCalculations.averageOrderValue(new BigDecimal("250.00"), 3)).isEqualByComparingTo("83.33");
        assertThat(ReportCalculations.averageOrderValue(new BigDecimal("0.05"), 2)).isEqualByComparingTo("0.03");
    }

    @Test
    void averageOrderValueIsZeroWithoutOrders() {
        assertThat(ReportCalculations.averageOrderValue(BigDecimal.ZERO, 0)).isEqualByComparingTo("0.00");
    }

    @Test
    void percentChangeIsRelativeToPreviousValue() {
        assertThat(ReportCalculations.percentChange(new BigDecimal("150"), new BigDecimal("100")))
                .isEqualByComparingTo("50.0");
        assertThat(ReportCalculations.percentChange(new BigDecimal("75"), new BigDecimal("100")))
                .isEqualByComparingTo("-25.0");
        assertThat(ReportCalculations.percentChange(new BigDecimal("250.00"), new BigDecimal("45.00")))
                .isEqualByComparingTo("455.6");
    }

    @Test
    void percentChangeIsNullWithoutBaseline() {
        assertThat(ReportCalculations.percentChange(new BigDecimal("10"), BigDecimal.ZERO)).isNull();
        assertThat(ReportCalculations.percentChange(new BigDecimal("10"), null)).isNull();
    }

    @Test
    void sharePercentHandlesZeroTotal() {
        assertThat(ReportCalculations.sharePercent(new BigDecimal("160"), new BigDecimal("250")))
                .isEqualByComparingTo("64.0");
        assertThat(ReportCalculations.sharePercent(BigDecimal.ZERO, BigDecimal.ZERO)).isEqualByComparingTo("0.0");
    }

    @Test
    void previousPeriodHasEqualLengthAndEndsTheDayBefore() {
        DateRange previous = ReportCalculations.previousPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(previous).isEqualTo(new DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)));
    }

    @Test
    void autoGranularityDependsOnRangeLength() {
        assertThat(Granularity.auto(1)).isEqualTo(Granularity.DAY);
        assertThat(Granularity.auto(62)).isEqualTo(Granularity.DAY);
        assertThat(Granularity.auto(63)).isEqualTo(Granularity.WEEK);
        assertThat(Granularity.auto(366)).isEqualTo(Granularity.WEEK);
        assertThat(Granularity.auto(367)).isEqualTo(Granularity.MONTH);
    }

    @Test
    void granularityParamIsCaseInsensitiveAndValidated() {
        assertThat(Granularity.fromParam("Week")).isEqualTo(Granularity.WEEK);
        assertThatThrownBy(() -> Granularity.fromParam("hour"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("day, week, month");
    }

    @Test
    void weeklyBucketsStartOnMondayAndCoverTheWholeRange() {
        // 2026-06-03 is a Wednesday; 2026-06-16 is a Tuesday.
        assertThat(ReportCalculations.bucketStarts(LocalDate.of(2026, 6, 3), LocalDate.of(2026, 6, 16), Granularity.WEEK))
                .containsExactly(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 8), LocalDate.of(2026, 6, 15));
    }

    @Test
    void partialBucketsReportTheDaysInsideTheRange() {
        LocalDate from = LocalDate.of(2026, 6, 3); // Wednesday
        LocalDate to = LocalDate.of(2026, 8, 31); // Monday

        assertThat(ReportCalculations.bucketDays(LocalDate.of(2026, 6, 1), Granularity.WEEK)).isEqualTo(7);
        assertThat(ReportCalculations.daysCovered(LocalDate.of(2026, 6, 1), Granularity.WEEK, from, to)).isEqualTo(5);
        assertThat(ReportCalculations.daysCovered(LocalDate.of(2026, 6, 8), Granularity.WEEK, from, to)).isEqualTo(7);
        assertThat(ReportCalculations.daysCovered(LocalDate.of(2026, 8, 31), Granularity.WEEK, from, to)).isEqualTo(1);
        assertThat(ReportCalculations.bucketDays(LocalDate.of(2026, 2, 1), Granularity.MONTH)).isEqualTo(28);
        assertThat(ReportCalculations.daysCovered(LocalDate.of(2026, 6, 1), Granularity.MONTH, from, to)).isEqualTo(28);
    }

    @Test
    void monthlyAndDailyBuckets() {
        assertThat(ReportCalculations.bucketStarts(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 5, 2), Granularity.MONTH))
                .containsExactly(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1));
        assertThat(ReportCalculations.bucketStarts(LocalDate.of(2026, 2, 27), LocalDate.of(2026, 3, 1), Granularity.DAY))
                .containsExactly(LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 1));
    }
}
