package com.oussamaksantini.insightstudio.chart;

import com.oussamaksantini.insightstudio.common.web.TooManyRequestsException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * At most {@code insight.charts.max-concurrent-runs-per-business} chart runs in progress per business on
 * this API instance (docs/dashboards-contract.md §4): a dashboard starts many widgets at once, and one
 * business must not take every database connection. A run over the limit is refused before any work
 * starts, with a 429 and {@code Retry-After: 1}; it never waits.
 *
 * <p>A counting semaphore per business, kept only while that business has runs in progress (the entry
 * is removed when its count drops to zero). Each instance counts its own runs.
 */
@Component
class ChartRunLimiter {

    static final String BUSY = "Too many charts are loading for this business right now. Try again in a moment.";

    private final int maxRuns;
    private final ConcurrentMap<Long, Integer> running = new ConcurrentHashMap<>();

    ChartRunLimiter(ChartProperties properties) {
        this.maxRuns = properties.maxConcurrentRunsPerBusiness();
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
        AtomicBoolean granted = new AtomicBoolean();
        running.compute(businessId, (id, count) -> {
            int now = count == null ? 0 : count;
            if (now >= maxRuns) {
                return count;
            }
            granted.set(true);
            return now + 1;
        });
        if (!granted.get()) {
            throw new TooManyRequestsException(BUSY, 1);
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                running.computeIfPresent(businessId, (id, count) -> count <= 1 ? null : count - 1);
            }
        };
    }

    /** Runs in progress for the business (for tests). */
    int running(long businessId) {
        return running.getOrDefault(businessId, 0);
    }
}
