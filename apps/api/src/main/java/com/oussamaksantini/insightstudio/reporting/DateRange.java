package com.oussamaksantini.insightstudio.reporting;

import java.time.LocalDate;

/** Inclusive calendar-date range in the business's time zone. */
public record DateRange(LocalDate from, LocalDate to) {
}
