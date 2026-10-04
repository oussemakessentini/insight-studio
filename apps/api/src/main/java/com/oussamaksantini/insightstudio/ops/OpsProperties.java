package com.oussamaksantini.insightstudio.ops;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code insight.ops.*} (docs/operations.md).
 *
 * @param jobOverdueAlert how long a queue item may wait past its due time before {@code jobs} health is DOWN
 * @param jobAttemptsAlert attempts of one item from which {@code jobs} health is DOWN (the workers log errors
 *     from the same count)
 */
@ConfigurationProperties("insight.ops")
public record OpsProperties(
        @DefaultValue("PT15M") Duration jobOverdueAlert,
        @DefaultValue("6") int jobAttemptsAlert) {
}
