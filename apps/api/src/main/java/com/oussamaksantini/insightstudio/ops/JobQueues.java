package com.oussamaksantini.insightstudio.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The durable work queues in PostgreSQL, measured the same on every instance (docs/operations.md,
 * "Background jobs"): how many items are pending, how long the most overdue one has waited past its due
 * time, and the most attempts a pending item has made. Refreshed every 15 s (one cheap query per queue),
 * so scrapes never hit the database. Metrics: {@code insight_jobs_pending{queue}},
 * {@code insight_jobs_overdue_seconds{queue}}, {@code insight_jobs_max_attempts{queue}}.
 */
@Component
public class JobQueues {

    private static final Logger log = LoggerFactory.getLogger(JobQueues.class);

    /** A queue: its name and the SQL selecting its pending rows (next_attempt_at, locked_until, attempts). */
    record Queue(String name, String pendingRows) {
    }

    static final List<Queue> QUEUES = List.of(
            new Queue("mail_outbox", "SELECT next_attempt_at, locked_until, attempts FROM mail_outbox WHERE status = 'PENDING'"),
            new Queue("cube_purge",
                    "SELECT next_attempt_at, locked_until, attempts FROM cube_purge_requests WHERE status = 'PENDING'"),
            new Queue("billing_events",
                    "SELECT next_attempt_at, locked_until, attempts FROM billing_events WHERE status = 'PENDING'"),
            new Queue("billing_cancellations",
                    "SELECT next_attempt_at, locked_until, attempts FROM billing_cancellations WHERE status = 'PENDING'"),
            new Queue("checkout_expiries", "SELECT next_attempt_at, locked_until, attempts FROM billing_operations "
                    + "WHERE kind = 'expire_checkout' AND status = 'PENDING'"));

    /** A queue's state at the last refresh. */
    public record QueueState(long pending, double overdueSeconds, int maxAttempts) {
    }

    private static final QueueState EMPTY = new QueueState(0, 0, 0);

    private final JdbcTemplate jdbc;
    private volatile Map<String, QueueState> states;

    JobQueues(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Map<String, QueueState> initial = new LinkedHashMap<>();
        QUEUES.forEach(q -> initial.put(q.name(), EMPTY));
        this.states = initial;
        for (Queue queue : QUEUES) {
            String name = queue.name();
            Gauge.builder("insight.jobs.pending", this, q -> q.state(name).pending()).tag("queue", name)
                    .description("Items waiting in the queue").register(registry);
            Gauge.builder("insight.jobs.overdue", this, q -> q.state(name).overdueSeconds()).tag("queue", name)
                    .baseUnit("seconds").description("How long the most overdue item has waited past its due time")
                    .register(registry);
            Gauge.builder("insight.jobs.max.attempts", this, q -> q.state(name).maxAttempts()).tag("queue", name)
                    .description("Most attempts made by a pending item").register(registry);
        }
    }

    /** The queues at the last refresh. */
    public Map<String, QueueState> states() {
        return states;
    }

    QueueState state(String queue) {
        return states.getOrDefault(queue, EMPTY);
    }

    /** Serialised: a refresh never overwrites a more recent one. */
    @Scheduled(fixedDelayString = "${insight.ops.queue-refresh:PT15S}", initialDelayString = "PT1S")
    public synchronized void refresh() {
        Map<String, QueueState> fresh = new LinkedHashMap<>();
        for (Queue queue : QUEUES) {
            try {
                fresh.put(queue.name(), jdbc.queryForObject("""
                        SELECT count(*) AS pending,
                               COALESCE(EXTRACT(EPOCH FROM now() - min(next_attempt_at) FILTER (
                                   WHERE next_attempt_at <= now() AND (locked_until IS NULL OR locked_until < now()))), 0)
                                   AS overdue,
                               COALESCE(max(attempts), 0) AS attempts
                        FROM (%s) q
                        """.formatted(queue.pendingRows()),
                        (rs, i) -> new QueueState(rs.getLong("pending"), rs.getDouble("overdue"), rs.getInt("attempts"))));
            } catch (RuntimeException e) {
                log.warn("Could not measure the {} queue: {}", queue.name(), e.getClass().getSimpleName());
                fresh.put(queue.name(), state(queue.name()));
            }
        }
        states = fresh;
    }
}
