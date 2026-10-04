package com.oussamaksantini.insightstudio.cubepurge;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code insight.cube-purge.*} (docs/account-management-api.md, "Cube purge").
 *
 * @param enabled whether this instance runs the worker (every instance may; they share the work)
 * @param pollInterval how often the worker looks for due purges
 * @param lease how long a claimed purge belongs to one worker (a crashed worker's purge is retried after it)
 * @param timeZones the zones Cube's refresh worker builds ({@code CUBEJS_SCHEDULED_REFRESH_TIMEZONES});
 *     the deleted business's zone and every remaining business's zone are always added
 * @param zoneTimeout how long one time zone's rollups may take to answer from fresh data
 * @param sweepDelay when, after the deletion, the worker forces one more rebuild so that Cube drops the
 *     superseded rollup tables still holding the deleted rows; must exceed both Cube's
 *     {@code CUBEJS_TOUCH_PRE_AGG_TIMEOUT} and {@code CUBEJS_DB_QUERY_TIMEOUT}
 * @param retryDelay wait after the first failed attempt; it doubles after each further failure
 * @param maxRetryDelay the longest wait between attempts (a purge is retried until it succeeds)
 * @param alertAfterAttempts failed attempts in a row after which each failure is logged as an error
 */
@ConfigurationProperties("insight.cube-purge")
public record CubePurgeProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT30S") Duration pollInterval,
        @DefaultValue("PT15M") Duration lease,
        @DefaultValue("") List<String> timeZones,
        @DefaultValue("PT2M") Duration zoneTimeout,
        @DefaultValue("PT70M") Duration sweepDelay,
        @DefaultValue("PT5M") Duration retryDelay,
        @DefaultValue("PT1H") Duration maxRetryDelay,
        @DefaultValue("6") int alertAfterAttempts) {

    public CubePurgeProperties {
        timeZones = timeZones == null ? List.of()
                : timeZones.stream().map(String::strip).filter(zone -> !zone.isEmpty()).toList();
        if (alertAfterAttempts < 1 || retryDelay.isNegative() || maxRetryDelay.compareTo(retryDelay) < 0 || zoneTimeout.isNegative() || zoneTimeout.isZero() || sweepDelay.isNegative()) {
            throw new IllegalStateException("Invalid insight.cube-purge settings");
        }
    }
}
