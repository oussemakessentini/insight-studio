package com.oussamaksantini.insightstudio.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * When each background job of this instance last completed a round, and when one last failed
 * (docs/operations.md, "Background jobs"). Workers report every poll; the metrics
 * {@code insight_job_last_success_seconds{job}} and {@code insight_job_last_failure_seconds{job}} (Unix time)
 * let alerts catch a worker that stopped running or keeps failing as a whole.
 */
@Component
public class JobHeartbeats {

    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<String, Instant> successes = new ConcurrentHashMap<>();
    private final Map<String, Instant> failures = new ConcurrentHashMap<>();
    private final Map<String, Boolean> registered = new ConcurrentHashMap<>();

    JobHeartbeats(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    /** A round of {@code job} completed (whatever it found to do). */
    public void succeeded(String job) {
        register(job);
        successes.put(job, clock.instant());
    }

    /** A round of {@code job} failed as a whole (single items are measured by {@link JobQueues}). */
    public void failed(String job) {
        register(job);
        failures.put(job, clock.instant());
    }

    /** The last successful round of each job that has run on this instance. */
    public Map<String, Instant> lastSuccesses() {
        return Map.copyOf(successes);
    }

    private void register(String job) {
        if (registered.putIfAbsent(job, Boolean.TRUE) != null) {
            return;
        }
        Gauge.builder("insight.job.last.success", successes, m -> seconds(m.get(job)))
                .tag("job", job).baseUnit("seconds")
                .description("Unix time of the job's last successful round on this instance (0: none yet)")
                .register(registry);
        Gauge.builder("insight.job.last.failure", failures, m -> seconds(m.get(job)))
                .tag("job", job).baseUnit("seconds")
                .description("Unix time of the job's last failed round on this instance (0: none)")
                .register(registry);
    }

    private static double seconds(Instant instant) {
        return instant == null ? 0 : instant.getEpochSecond();
    }
}
