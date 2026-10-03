package com.oussamaksantini.insightstudio.report;

import com.oussamaksantini.insightstudio.analytics.CubeAnswer;
import com.oussamaksantini.insightstudio.analytics.CubeClient;
import com.oussamaksantini.insightstudio.analytics.CubeClient.CacheMode;
import com.oussamaksantini.insightstudio.analytics.CubeException;
import com.oussamaksantini.insightstudio.common.web.ServiceUnavailableException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Runs Cube queries so that their figures are never older than the data (docs/cube-reports.md,
 * "Freshness"), within one deadline, and maps every failure to the 503 answers of
 * docs/cube-reports-contract.md §5. Shared by the report engine ({@link CubeReportEngine}) and the
 * chart builder's Cube engine, so both follow exactly the same rules.
 *
 * <p>{@link #run} first reads {@code report_data_version} (Flyway V11). Every query must return its
 * cube's {@code data_version}: the version read by the same SQL statement as the rows it was computed
 * from (a rollup build, or the query itself). A result counts only when that version is at least the
 * one read at the start. When an answer is older, the whole computation is asked again with
 * {@code "cache": "must-revalidate"}, which makes Cube re-read its refresh keys and rebuild, until the
 * deadline. The answers of one computation must also come from a single version
 * ({@link Attempt#requireOneVersion}).
 *
 * <p>Failures are 503 problem details with a {@code Retry-After}; Cube's own messages are only logged.
 * One deadline ({@code insight.reports.cube-timeout}) covers all Cube calls of a request.
 */
public final class CubeFreshness {

    public static final String UNAVAILABLE = "Report figures are temporarily unavailable. Try again in a minute.";
    public static final String UPDATING = "Report figures are being updated after recent changes. Try again in a few seconds.";
    /** Cube unreachable, HTTP error or invalid answer. */
    public static final long RETRY_AFTER_UNAVAILABLE = 60;
    /** Cube still working ("Continue wait") at the deadline. */
    public static final long RETRY_AFTER_BUILDING = 30;
    /** Cube's data still older than the data version at the deadline. */
    public static final long RETRY_AFTER_UPDATING = 5;

    /** Row limit sent with every query; an answer with this many rows is treated as invalid. */
    public static final int ROW_LIMIT = 10_000;

    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private static final Logger log = LoggerFactory.getLogger(CubeFreshness.class);

    /** One {@code /load} call (see {@link CubeClient#send}). */
    @FunctionalInterface
    public interface Cube {
        CubeAnswer send(long businessId, Map<String, Object> query, CacheMode cache, Duration timeout);
    }

    private final Cube cube;
    private final LongSupplier dataVersion;
    private final Duration timeout;

    /** @param dataVersion reads the current {@code report_data_version} */
    public CubeFreshness(Cube cube, LongSupplier dataVersion, Duration timeout) {
        this.cube = cube;
        this.dataVersion = dataVersion;
        this.timeout = timeout;
    }

    /** Wired to Cube and the database. */
    public static CubeFreshness create(CubeClient client, NamedParameterJdbcTemplate jdbc, Duration timeout) {
        return new CubeFreshness(client::send, () -> currentDataVersion(jdbc), timeout);
    }

    public Duration timeout() {
        return timeout;
    }

    /** Runs {@code computation} until every answer is at least as new as the data version, or the deadline. */
    public <T> T run(long businessId, Function<Attempt, T> computation) {
        long required = dataVersion.getAsLong();
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean revalidate = false;
        while (true) {
            Attempt attempt = new Attempt(businessId, required, deadline, revalidate);
            try {
                return computation.apply(attempt);
            } catch (StaleAnswer e) {
                log.info("Cube answered with data version {} for business {}, but the data is at version {}; {}",
                        e.version < 0 ? "(none)" : e.version, businessId, required,
                        revalidate ? "asking again" : "revalidating");
                if (System.nanoTime() >= deadline) {
                    log.warn("Cube's data for business {} was still older than version {} after {}",
                            businessId, required, timeout);
                    throw updating();
                }
                if (revalidate) {
                    pause(deadline);
                }
                revalidate = true;
            }
        }
    }

    /** One attempt at a computation: its Cube calls share the request's deadline. */
    public final class Attempt {

        private final long businessId;
        private final long required;
        private final long deadline;
        private final boolean revalidate;

        Attempt(long businessId, long required, long deadline, boolean revalidate) {
            this.businessId = businessId;
            this.required = required;
            this.deadline = deadline;
            this.revalidate = revalidate;
        }

        /**
         * The rows of {@code rollupQuery} when they carry a version; otherwise (a period without
         * sales) those of {@code verifiedQuery}, which always does. A version older than the one
         * required throws {@link StaleAnswer}.
         */
        public Verified verified(Map<String, Object> rollupQuery, Map<String, Object> verifiedQuery, String versionMember) {
            List<Map<String, Object>> rows = load(rollupQuery, revalidate ? CacheMode.MUST_REVALIDATE : CacheMode.DEFAULT);
            OptionalLong version = version(rows, versionMember);
            if (version.isEmpty() && verifiedQuery != null) {
                // Not served by a rollup: Cube's result cache is keyed by refresh-key values it may
                // reuse even when revalidating, so this query always runs on the database.
                rows = load(verifiedQuery, CacheMode.NO_CACHE);
                version = version(rows, versionMember);
            }
            return check(rows, version);
        }

        /**
         * The rows of a query that Cube cannot serve from a rollup (it runs as one statement on
         * PostgreSQL, so rows and version come from one snapshot). The query must include the cube's
         * marker rows, so that it always carries a version.
         */
        public Verified database(Map<String, Object> verifiedQuery, String versionMember) {
            List<Map<String, Object>> rows = load(verifiedQuery, CacheMode.NO_CACHE);
            return check(rows, version(rows, versionMember));
        }

        private Verified check(List<Map<String, Object>> rows, OptionalLong version) {
            if (version.isEmpty()) {
                // No marker row: the build predates the business (inserting one does not bump the data
                // version, but it is part of the refresh key, so a revalidation rebuilds).
                throw new StaleAnswer(-1);
            }
            if (version.getAsLong() < required) {
                throw new StaleAnswer(version.getAsLong());
            }
            return new Verified(rows, version.getAsLong());
        }

        /** The figures of one computation must come from one data version. */
        public void requireOneVersion(Verified... answers) {
            Set<Long> versions = new TreeSet<>();
            for (Verified answer : answers) {
                if (answer != null) {
                    versions.add(answer.version());
                }
            }
            if (versions.size() > 1) {
                throw new StaleAnswer(versions.iterator().next());
            }
        }

        private List<Map<String, Object>> load(Map<String, Object> query, CacheMode cache) {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw outOfTime();
                }
                CubeAnswer answer;
                try {
                    answer = cube.send(businessId, query, cache, Duration.ofNanos(remaining));
                } catch (CubeException e) {
                    if (e.timedOut()) {
                        // Cube is still working (its "Continue wait" did not come in time): ask again
                        // until the deadline, like after "Continue wait".
                        continue;
                    }
                    throw unavailable();
                }
                if (answer.continueWait()) {
                    pause(deadline);
                    continue;
                }
                if (answer.data().size() >= ROW_LIMIT) {
                    log.warn("Cube returned {} rows, the row limit; the answer may be incomplete", answer.data().size());
                    throw unavailable();
                }
                log.debug("Cube answered {} rows for business {} (pre-aggregations {})",
                        answer.data().size(), businessId, answer.preAggregations());
                return answer.data();
            }
        }

        /** Out of time while Cube was still working: on fresh data (30 s) or on a revalidation (5 s). */
        private ServiceUnavailableException outOfTime() {
            log.warn("Cube did not answer within {} for business {}{}", timeout, businessId,
                    revalidate ? " while updating to the current data version" : "");
            return revalidate ? updating() : new ServiceUnavailableException(UNAVAILABLE, RETRY_AFTER_BUILDING);
        }
    }

    /** Rows whose data version has been checked. */
    public record Verified(List<Map<String, Object>> rows, long version) {
    }

    /** An answer older than the required data version (or without one). */
    private static final class StaleAnswer extends RuntimeException {

        private final long version;

        StaleAnswer(long version) {
            super(null, null, false, false);
            this.version = version;
        }
    }

    // ---------------------------------------------------------------- reading Cube's rows

    /**
     * The data version of an answer: the value of {@code member} shared by its rows, or empty when
     * no row has one (no data and no marker row). Rows of one answer come from one statement or
     * build, so they must agree; anything else is an invalid answer.
     */
    public static OptionalLong version(List<Map<String, Object>> rows, String member) {
        Set<Long> versions = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Object value = row.get(member);
            if (value != null) {
                versions.add(count(value));
            }
        }
        if (versions.size() > 1) {
            log.warn("Cube returned rows of several data versions {} in one answer", versions);
            throw unavailable();
        }
        return versions.isEmpty() ? OptionalLong.empty() : OptionalLong.of(versions.iterator().next());
    }

    /**
     * Money as Cube sends it (a decimal string, or {@code null} without sales), at scale 2. Revenue is
     * a sum of {@code quantity * unit_price} with {@code unit_price NUMERIC(12,2)}, so it is exact in
     * cents and the rounding only removes trailing zeros or binary noise.
     */
    public static BigDecimal money(Object value) {
        return decimal(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** A count, version or id as Cube sends it ({@code "3"}, or {@code null} for none). */
    public static long count(Object value) {
        try {
            return decimal(value).longValueExact();
        } catch (ArithmeticException e) {
            log.warn("Cube returned a non-integer count");
            throw unavailable();
        }
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            log.warn("Cube returned a non-numeric value");
            throw unavailable();
        }
    }

    /** A time bucket such as {@code "2026-04-01T00:00:00.000"} (local time in the query's zone). */
    public static LocalDate localDate(Object value) {
        String text = value.toString();
        try {
            return LocalDate.parse(text.length() >= 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException e) {
            log.warn("Cube returned an invalid date: {}", text);
            throw unavailable();
        }
    }

    // ---------------------------------------------------------------- database and failures

    static long currentDataVersion(NamedParameterJdbcTemplate jdbc) {
        Long version = jdbc.queryForObject("SELECT version FROM report_data_version WHERE id = 1", Map.of(), Long.class);
        if (version == null) {
            throw new IllegalStateException("report_data_version has no row");
        }
        return version;
    }

    public static ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException(UNAVAILABLE, RETRY_AFTER_UNAVAILABLE);
    }

    private static ServiceUnavailableException updating() {
        return new ServiceUnavailableException(UPDATING, RETRY_AFTER_UPDATING);
    }

    private static void pause(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            Thread.sleep(Duration.ofNanos(Math.min(remaining, POLL_INTERVAL.toNanos())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }
}
