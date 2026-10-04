package com.oussamaksantini.insightstudio.ops;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * {@code jobs} health ({@code /actuator/health/jobs}; never part of liveness or readiness, so a stuck queue
 * does not take healthy instances out of service): {@code DOWN} when an item of a queue is overdue by more
 * than {@code insight.ops.job-overdue-alert} (15 minutes) or has failed {@code insight.ops.job-attempts-alert}
 * (6) times. Details list every queue.
 */
@Component("backgroundJobs")
class JobsHealthIndicator implements HealthIndicator {

    private final JobQueues queues;
    private final OpsProperties properties;

    JobsHealthIndicator(JobQueues queues, OpsProperties properties) {
        this.queues = queues;
        this.properties = properties;
    }

    @Override
    public Health health() {
        boolean healthy = true;
        Map<String, Object> details = new LinkedHashMap<>();
        for (Map.Entry<String, JobQueues.QueueState> entry : queues.states().entrySet()) {
            JobQueues.QueueState state = entry.getValue();
            boolean late = state.overdueSeconds() > properties.jobOverdueAlert().toSeconds();
            boolean failing = state.maxAttempts() >= properties.jobAttemptsAlert();
            healthy &= !late && !failing;
            Map<String, Object> queue = new LinkedHashMap<>();
            queue.put("pending", state.pending());
            queue.put("overdue", Duration.ofSeconds((long) state.overdueSeconds()).toString());
            queue.put("maxAttempts", state.maxAttempts());
            queue.put("status", late ? "DELAYED" : failing ? "FAILING" : "OK");
            details.put(entry.getKey(), queue);
        }
        return (healthy ? Health.up() : Health.down()).withDetails(details).build();
    }
}
