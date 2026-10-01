package com.oussamaksantini.insightstudio.savedreport;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Turns a saved range into concrete dates: fixed ranges as stored, relative ones against today in
 * the business's time zone (not the server's), read from the injectable {@link Clock}.
 */
@Component
public class PeriodResolver {

    private final Clock clock;

    public PeriodResolver(Clock clock) {
        this.clock = clock;
    }

    public DateRange resolve(SavedRange range, ZoneId businessZone) {
        if (!range.isRelative()) {
            return new DateRange(range.from(), range.to());
        }
        return range.preset().resolve(today(businessZone));
    }

    public LocalDate today(ZoneId businessZone) {
        return LocalDate.now(clock.withZone(businessZone));
    }
}
