package com.oussamaksantini.insightstudio.chart;

import com.oussamaksantini.insightstudio.common.web.TooManyRequestsException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * At most {@code insight.charts.max-concurrent-runs-per-business} chart runs in progress per business
 * <b>across every API instance</b> (docs/dashboards-contract.md §4): a dashboard starts many widgets at
 * once, and one business must not take every database connection. A run over the limit is refused before
 * any work starts, with a 429 and {@code Retry-After: 1}; it never waits.
 *
 * <p>Slots are rows of {@code chart_run_slots} (Flyway V20). Taking one is a short transaction serialised
 * per business by an advisory lock (count the live rows, insert one if there is room), so concurrent runs
 * on several instances never exceed the limit and are never refused while a slot is free. Releasing deletes
 * the row. A slot whose instance died stops counting after {@code insight.charts.run-slot-ttl}.
 */
@Component
class ChartRunLimiter {

    private static final Logger log = LoggerFactory.getLogger(ChartRunLimiter.class);
    static final String BUSY = "Too many charts are loading for this business right now. Try again in a moment.";

    private final int maxRuns;
    private final long ttlSeconds;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    ChartRunLimiter(ChartProperties properties, NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.maxRuns = properties.maxConcurrentRunsPerBusiness();
        this.ttlSeconds = properties.runSlotTtl().toSeconds();
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    /** A run of the business released by closing the permit (try-with-resources). */
    interface Permit extends AutoCloseable {

        @Override
        void close();
    }

    /**
     * Takes one of the business's run slots.
     *
     * @throws TooManyRequestsException 429 when all of them are taken
     */
    Permit acquire(long businessId) {
        UUID holder = UUID.randomUUID();
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("b", businessId).addValue("h", holder)
                .addValue("n", maxRuns).addValue("ttl", ttlSeconds);
        boolean granted = Boolean.TRUE.equals(transactions.execute(status -> {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended('chart-runs:' || :b, 0))", params);
            jdbc.update("DELETE FROM chart_run_slots WHERE business_id = :b AND expires_at <= now()", params);
            return jdbc.update("""
                    INSERT INTO chart_run_slots (holder, business_id, expires_at)
                    SELECT :h, :b, now() + make_interval(secs => :ttl)
                    WHERE (SELECT count(*) FROM chart_run_slots WHERE business_id = :b) < :n
                    """, params) == 1;
        }));
        if (!granted) {
            throw new TooManyRequestsException(BUSY, 1);
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                try {
                    jdbc.update("DELETE FROM chart_run_slots WHERE holder = :h", params);
                } catch (RuntimeException e) {
                    // The slot expires on its own (run-slot-ttl); never fail the response for it.
                    log.warn("Could not release a chart run slot of business {}: {}", businessId, e.getClass().getSimpleName());
                }
            }
        };
    }

    /** Live runs of the business on every instance (for tests and metrics). */
    int running(long businessId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM chart_run_slots WHERE business_id = :b AND expires_at > now()",
                new MapSqlParameterSource("b", businessId), Integer.class);
        return count == null ? 0 : count;
    }
}
