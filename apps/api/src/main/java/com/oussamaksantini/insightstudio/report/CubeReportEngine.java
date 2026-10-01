package com.oussamaksantini.insightstudio.report;

import static com.oussamaksantini.insightstudio.report.CubeReportQueries.CATEGORY;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.CATEGORY_ORDERS;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.CATEGORY_REVENUE;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.CATEGORY_UNITS;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.CATEGORY_VERSION;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.ORDERS_COUNT;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.ORDERS_MONTH;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.ORDERS_REVENUE;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.ORDERS_UNITS;
import static com.oussamaksantini.insightstudio.report.CubeReportQueries.ORDERS_VERSION;

import com.oussamaksantini.insightstudio.analytics.CubeAnswer;
import com.oussamaksantini.insightstudio.analytics.CubeClient;
import com.oussamaksantini.insightstudio.analytics.CubeClient.CacheMode;
import com.oussamaksantini.insightstudio.analytics.CubeException;
import com.oussamaksantini.insightstudio.common.web.ServiceUnavailableException;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Cube report engine ({@code insight.reports.engine=cube}): the reports' raw totals from Cube's
 * REST API (docs/cube-reports.md), never older than the data they are asked about.
 *
 * <p><b>Freshness.</b> Each request first reads {@code report_data_version} (Flyway V11, bumped in the
 * same transaction as any change to sales, sale items, products or stores). Every Cube query returns
 * the cube's {@code data_version}: the version read by the same SQL statement as the rows it was
 * computed from (a rollup build, or the query itself). A result counts only when that version is at
 * least the one read at the start; the queries are built so that every answer carries it
 * ({@link CubeReportQueries}). When an answer is older, the whole report is asked again with
 * {@code "cache": "must-revalidate"}, which makes Cube re-read its refresh keys and rebuild, until
 * the deadline. The figures of one report must also come from a single version.
 *
 * <p><b>Failures</b> (docs/cube-reports-contract.md §5) are 503 problem details with a
 * {@code Retry-After}; Cube's own messages are only logged. One deadline ({@code cube-timeout})
 * covers all Cube calls of a request.
 */
final class CubeReportEngine implements ReportEngine {

    static final String NAME = "cube";
    static final String UNAVAILABLE = "Report figures are temporarily unavailable. Try again in a minute.";
    static final String UPDATING = "Report figures are being updated after recent changes. Try again in a few seconds.";
    /** Cube unreachable, HTTP error or invalid answer. */
    static final long RETRY_AFTER_UNAVAILABLE = 60;
    /** Cube still working ("Continue wait") at the deadline. */
    static final long RETRY_AFTER_BUILDING = 30;
    /** Cube's data still older than the data version at the deadline. */
    static final long RETRY_AFTER_UPDATING = 5;

    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private static final Logger log = LoggerFactory.getLogger(CubeReportEngine.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One {@code /load} call (see {@link CubeClient#send}). */
    @FunctionalInterface
    interface Cube {
        CubeAnswer send(long businessId, Map<String, Object> query, CacheMode cache, Duration timeout);
    }

    private final Cube cube;
    private final LongSupplier dataVersion;
    private final UnaryOperator<List<CategoryTotals>> categoryOrder;
    private final Duration timeout;

    /**
     * @param dataVersion reads the current {@code report_data_version}
     * @param categoryOrder sorts category rows by revenue (highest first), then name in the database's
     *     collation, exactly like {@link SqlReportEngine}
     */
    CubeReportEngine(
            Cube cube, LongSupplier dataVersion, UnaryOperator<List<CategoryTotals>> categoryOrder, Duration timeout) {
        this.cube = cube;
        this.dataVersion = dataVersion;
        this.categoryOrder = categoryOrder;
        this.timeout = timeout;
    }

    /** The engine wired to Cube and the database. */
    static CubeReportEngine create(CubeClient client, NamedParameterJdbcTemplate jdbc, Duration timeout) {
        return new CubeReportEngine(client::send, () -> currentDataVersion(jdbc), rows -> orderInDatabase(jdbc, rows),
                timeout);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<MonthTotals> monthly(ReportFilter filter) {
        return run(filter, attempt -> {
            Verified answer = attempt.verified(
                    CubeReportQueries.monthly(filter), CubeReportQueries.monthlyVerified(filter), ORDERS_VERSION);
            List<MonthTotals> months = new ArrayList<>();
            for (Map<String, Object> row : answer.rows()) {
                Object month = row.get(ORDERS_MONTH);
                if (month == null) {
                    continue; // the business's marker row
                }
                months.add(new MonthTotals(
                        localDate(month), money(row.get(ORDERS_REVENUE)), count(row.get(ORDERS_COUNT)),
                        count(row.get(ORDERS_UNITS))));
            }
            months.sort(Comparator.comparing(MonthTotals::month));
            return months;
        });
    }

    @Override
    public CategoryBreakdown categories(ReportFilter filter) {
        return run(filter, attempt -> {
            Verified sold = attempt.verified(
                    CubeReportQueries.categories(filter), CubeReportQueries.categoriesVerified(filter), CATEGORY_VERSION);
            Verified catalogue = attempt.verified(CubeReportQueries.catalogue(filter), null, CATEGORY_VERSION);
            Verified orders = attempt.verified(
                    CubeReportQueries.orderTotals(filter), CubeReportQueries.orderTotalsVerified(filter), ORDERS_VERSION);
            attempt.requireOneVersion(sold, catalogue, orders);

            // Every catalogue category, with its figures when it sold in the period (zeros otherwise).
            Map<String, CategoryTotals> byCategory = new LinkedHashMap<>();
            for (Map<String, Object> row : catalogue.rows()) {
                if (row.get(CATEGORY) instanceof String name) {
                    byCategory.put(name, new CategoryTotals(name, money(null), 0, 0));
                }
            }
            for (Map<String, Object> row : sold.rows()) {
                if (row.get(CATEGORY) instanceof String name) {
                    byCategory.put(name, new CategoryTotals(name, money(row.get(CATEGORY_REVENUE)),
                            count(row.get(CATEGORY_UNITS)), count(row.get(CATEGORY_ORDERS))));
                }
            }
            BigDecimal revenue = money(null);
            long units = 0;
            for (CategoryTotals category : byCategory.values()) {
                revenue = revenue.add(category.revenue());
                units += category.units();
            }
            long totalOrders = orders.rows().isEmpty() ? 0 : count(orders.rows().getFirst().get(ORDERS_COUNT));
            List<CategoryTotals> rows = byCategory.isEmpty()
                    ? List.of()
                    : categoryOrder.apply(List.copyOf(byCategory.values()));
            return new CategoryBreakdown(rows, new CategoryTotals(null, revenue, units, totalOrders));
        });
    }

    // ---------------------------------------------------------------- freshness and retries

    /** Runs {@code report} until every answer is at least as new as the data version, or the deadline. */
    private <T> T run(ReportFilter filter, Function<Attempt, T> report) {
        long required = dataVersion.getAsLong();
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean revalidate = false;
        while (true) {
            Attempt attempt = new Attempt(filter.businessId(), required, deadline, revalidate);
            try {
                return report.apply(attempt);
            } catch (StaleAnswer e) {
                log.info("Cube answered with data version {} for business {}, but the data is at version {}; {}",
                        e.version < 0 ? "(none)" : e.version, filter.businessId(), required,
                        revalidate ? "asking again" : "revalidating");
                if (System.nanoTime() >= deadline) {
                    log.warn("Cube's data for business {} was still older than version {} after {}",
                            filter.businessId(), required, timeout);
                    throw updating();
                }
                if (revalidate) {
                    pause(deadline);
                }
                revalidate = true;
            }
        }
    }

    /** One attempt at a report: its Cube calls share the request's deadline. */
    private final class Attempt {

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
        Verified verified(Map<String, Object> rollupQuery, Map<String, Object> verifiedQuery, String versionMember) {
            List<Map<String, Object>> rows = load(rollupQuery, revalidate ? CacheMode.MUST_REVALIDATE : CacheMode.DEFAULT);
            OptionalLong version = version(rows, versionMember);
            if (version.isEmpty() && verifiedQuery != null) {
                // Not served by a rollup: Cube's result cache is keyed by refresh-key values it may
                // reuse even when revalidating, so this query always runs on the database.
                rows = load(verifiedQuery, CacheMode.NO_CACHE);
                version = version(rows, versionMember);
            }
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

        /** The figures of one report must come from one data version. */
        void requireOneVersion(Verified... answers) {
            Set<Long> versions = new TreeSet<>();
            for (Verified answer : answers) {
                versions.add(answer.version());
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
                if (answer.data().size() >= CubeReportQueries.ROW_LIMIT) {
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
    record Verified(List<Map<String, Object>> rows, long version) {
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
    static OptionalLong version(List<Map<String, Object>> rows, String member) {
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
    static BigDecimal money(Object value) {
        return decimal(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** A count or version as Cube sends it ({@code "3"}, or {@code null} for none). */
    static long count(Object value) {
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

    /** A month bucket such as {@code "2026-04-01T00:00:00.000"} (local time in the query's zone). */
    static LocalDate localDate(Object value) {
        String text = value.toString();
        try {
            return LocalDate.parse(text.length() >= 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException e) {
            log.warn("Cube returned an invalid month: {}", text);
            throw unavailable();
        }
    }

    // ---------------------------------------------------------------- database helpers

    static long currentDataVersion(NamedParameterJdbcTemplate jdbc) {
        Long version = jdbc.queryForObject("SELECT version FROM report_data_version WHERE id = 1", Map.of(), Long.class);
        if (version == null) {
            throw new IllegalStateException("report_data_version has no row");
        }
        return version;
    }

    /**
     * Sorts like {@link SqlReportEngine}'s {@code ORDER BY revenue DESC, p.category}, in the database,
     * so names tie-break in the same collation.
     */
    static List<CategoryTotals> orderInDatabase(NamedParameterJdbcTemplate jdbc, List<CategoryTotals> rows) {
        List<Map<String, String>> values = rows.stream()
                .map(row -> Map.of("category", row.category(), "revenue", row.revenue().toPlainString()))
                .toList();
        Map<String, CategoryTotals> byName = new LinkedHashMap<>();
        rows.forEach(row -> byName.put(row.category(), row));
        List<String> ordered = jdbc.queryForList("""
                SELECT t.category
                FROM jsonb_to_recordset(CAST(:rows AS jsonb)) AS t(category varchar, revenue numeric)
                ORDER BY t.revenue DESC, t.category
                """, Map.of("rows", JSON.writeValueAsString(values)), String.class);
        return ordered.stream().map(byName::get).toList();
    }

    // ---------------------------------------------------------------- failures

    private static ServiceUnavailableException unavailable() {
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
