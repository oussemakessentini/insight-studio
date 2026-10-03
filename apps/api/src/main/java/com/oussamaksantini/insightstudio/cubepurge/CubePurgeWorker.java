package com.oussamaksantini.insightstudio.cubepurge;

import com.oussamaksantini.insightstudio.analytics.CubeAnswer;
import com.oussamaksantini.insightstudio.analytics.CubeClient;
import com.oussamaksantini.insightstudio.analytics.CubeClient.CacheMode;
import com.oussamaksantini.insightstudio.analytics.CubeException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purges a deleted business's rows from Cube's rollups ({@code cube_purge_requests}, Flyway V16;
 * docs/account-management-api.md, "Cube purge"). Rollups are shared by every business, so nothing can
 * drop "the business's rollup": every rollup is rebuilt instead, and Cube drops the superseded tables.
 *
 * <p>Any number of API instances may run it: requests are claimed with {@code FOR UPDATE SKIP LOCKED}
 * and a lease ({@code locked_until}), like the mail outbox. A request goes through two phases:
 * <ol>
 *   <li><b>Rebuild</b> (at once): for every time zone (configured, the deleted business's, every
 *       remaining business's), every rollup is asked with {@code "cache": "must-revalidate"} until it
 *       answers from a data version at least the request's ({@code report_data_version} after the
 *       deletion), so no rollup Cube serves holds the deleted rows any more.</li>
 *   <li><b>Sweep</b> ({@code sweep-delay} after the deletion): Cube 1.7.46 drops superseded tables only
 *       while building another table, and keeps those touched or used recently
 *       ({@code CUBEJS_TOUCH_PRE_AGG_TIMEOUT}, {@code CUBEJS_DB_QUERY_TIMEOUT}). Once both have passed,
 *       the worker bumps {@code report_data_version} and rebuilds everything again; those builds drop
 *       every table older than the current ones. Then the request is DONE.</li>
 * </ol>
 * Without a Cube connection the request is SKIPPED (nothing was ever stored in Cube by this API).
 * A phase that fails is retried after {@code retry-delay}, at most {@code max-attempts} times, then
 * the request is FAILED with the error (logged, never shown to users).
 */
@Component
@EnableConfigurationProperties(CubePurgeProperties.class)
@ConditionalOnProperty(name = "insight.cube-purge.enabled", havingValue = "true", matchIfMissing = true)
public class CubePurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(CubePurgeWorker.class);
    private static final Duration PAUSE = Duration.ofMillis(500);
    private static final int MAX_ERROR = 500;

    /** One query per rollup of services/analytics/model (no time dimension: the marker rows carry the version). */
    record RollupQuery(String rollup, String measure, String versionMember) {
    }

    static final List<RollupQuery> ROLLUPS = List.of(
            new RollupQuery("orders.daily_by_store", "orders.count", "orders.data_version"),
            new RollupQuery("order_categories.daily_by_store_category", "order_categories.count", "order_categories.data_version"),
            new RollupQuery("order_products.daily_by_store_product", "order_products.count", "order_products.data_version"),
            // No data_version in line_items: asked last, once the shared refresh key has moved on.
            new RollupQuery("line_items.daily_by_store_category", "line_items.count", null));

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectProvider<CubeClient> cube;
    private final CubePurgeProperties settings;

    CubePurgeWorker(NamedParameterJdbcTemplate jdbc, ObjectProvider<CubeClient> cube, CubePurgeProperties settings) {
        this.jdbc = jdbc;
        this.cube = cube;
        this.settings = settings;
    }

    record Claimed(long id, long businessId, String timeZone, long dataVersion, int attempts, boolean sweep) {
    }

    @Scheduled(fixedDelayString = "${insight.cube-purge.poll-interval:PT30S}",
            initialDelayString = "${insight.cube-purge.poll-interval:PT30S}")
    void poll() {
        try {
            processDue();
        } catch (RuntimeException e) {
            log.warn("Cube purge round failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Handles every due request; returns how many were handled. */
    public int processDue() {
        int handled = 0;
        Claimed claimed;
        while ((claimed = claim()) != null) {
            process(claimed);
            handled++;
        }
        return handled;
    }

    /** Claims one due request (one statement: the claim is committed at once). */
    private Claimed claim() {
        return jdbc.query("""
                UPDATE cube_purge_requests r
                SET attempts = r.attempts + 1, locked_until = now() + make_interval(secs => :lease)
                FROM (
                    SELECT id FROM cube_purge_requests
                    WHERE status = 'PENDING' AND next_attempt_at <= now()
                      AND (locked_until IS NULL OR locked_until < now())
                    ORDER BY next_attempt_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                ) due
                WHERE r.id = due.id
                RETURNING r.id, r.business_id, r.time_zone, r.data_version, r.attempts,
                          now() >= r.created_at + make_interval(secs => :sweep) AS sweep
                """, new MapSqlParameterSource()
                        .addValue("lease", settings.lease().toSeconds())
                        .addValue("sweep", settings.sweepDelay().toSeconds()),
                (rs, i) -> new Claimed(rs.getLong("id"), rs.getLong("business_id"), rs.getString("time_zone"),
                        rs.getLong("data_version"), rs.getInt("attempts"), rs.getBoolean("sweep")))
                .stream().findFirst().orElse(null);
    }

    private void process(Claimed request) {
        CubeClient client = cube.getIfAvailable();
        if (client == null) {
            finish(request.id(), "SKIPPED", null);
            log.info("Cube purge {} (business {}) skipped: no Cube is configured.", request.id(), request.businessId());
            return;
        }
        try {
            if (request.sweep()) {
                long version = jdbc.queryForObject("""
                        UPDATE report_data_version SET version = version + 1, updated_at = now() WHERE id = 1 RETURNING version
                        """, Map.of(), Long.class);
                rebuild(client, request, version);
                finish(request.id(), "DONE", null);
                log.info("Cube purge {} (business {}) done: superseded rollup tables swept at data version {}.",
                        request.id(), request.businessId(), version);
            } else {
                rebuild(client, request, request.dataVersion());
                jdbc.update("""
                        UPDATE cube_purge_requests
                        SET attempts = 0, locked_until = NULL, last_error = NULL,
                            next_attempt_at = created_at + make_interval(secs => :sweep)
                        WHERE id = :id
                        """, new MapSqlParameterSource().addValue("id", request.id())
                                .addValue("sweep", settings.sweepDelay().toSeconds()));
                log.info("Cube purge {} (business {}): every rollup rebuilt from data version {} or newer; sweep in {}.",
                        request.id(), request.businessId(), request.dataVersion(), settings.sweepDelay());
            }
        } catch (RuntimeException e) {
            failed(request, describe(e));
        }
    }

    /** Asks every rollup in every relevant zone until it answers from {@code version} or newer. */
    void rebuild(CubeClient client, Claimed request, long version) {
        Long anyBusiness = jdbc.queryForList("SELECT id FROM businesses ORDER BY id LIMIT 1", Map.of(), Long.class)
                .stream().findFirst().orElse(null);
        // The token must name a business; any remaining one carries the version in its marker rows.
        long tokenBusiness = anyBusiness == null ? request.businessId() : anyBusiness;
        for (String zone : zones(request.timeZone())) {
            long deadline = System.nanoTime() + settings.zoneTimeout().toNanos();
            for (RollupQuery rollup : ROLLUPS) {
                ask(client, tokenBusiness, zone, rollup, anyBusiness == null ? 0 : version, deadline);
            }
        }
    }

    private void ask(CubeClient client, long tokenBusiness, String zone, RollupQuery rollup, long version, long deadline) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("measures", rollup.versionMember() == null ? List.of(rollup.measure())
                : List.of(rollup.measure(), rollup.versionMember()));
        query.put("timezone", zone);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IllegalStateException("%s in %s did not answer from data version %d in time"
                        .formatted(rollup.rollup(), zone, version));
            }
            CubeAnswer answer;
            try {
                answer = client.send(tokenBusiness, query, CacheMode.MUST_REVALIDATE, Duration.ofNanos(remaining));
            } catch (CubeException e) {
                if (!e.timedOut()) {
                    throw e;
                }
                continue;
            }
            if (!answer.continueWait() && fresh(answer, rollup, version)) {
                return;
            }
            pause();
        }
    }

    /** Whether the answer comes from {@code version} or newer (rollups without a version: any answer). */
    static boolean fresh(CubeAnswer answer, RollupQuery rollup, long version) {
        if (rollup.versionMember() == null || version <= 0) {
            return true;
        }
        return answer.data().stream()
                .map(row -> row.get(rollup.versionMember()))
                .filter(value -> value != null)
                .anyMatch(value -> Long.parseLong(value.toString()) >= version);
    }

    /** The configured zones, the deleted business's and every remaining business's. */
    Set<String> zones(String deletedZone) {
        Set<String> zones = new LinkedHashSet<>(settings.timeZones());
        zones.add(deletedZone);
        zones.addAll(jdbc.queryForList("SELECT DISTINCT time_zone FROM businesses ORDER BY time_zone", Map.of(), String.class));
        return zones;
    }

    private void failed(Claimed request, String error) {
        if (request.attempts() >= settings.maxAttempts()) {
            finish(request.id(), "FAILED", error);
            log.error("Cube purge {} (business {}) failed after {} attempts: {}. Rebuild Cube's rollups by hand "
                    + "(docs/account-management-api.md).", request.id(), request.businessId(), request.attempts(), error);
            return;
        }
        jdbc.update("""
                UPDATE cube_purge_requests
                SET locked_until = NULL, last_error = :error, next_attempt_at = now() + make_interval(secs => :wait)
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", request.id()).addValue("error", error)
                        .addValue("wait", settings.retryDelay().toSeconds()));
        log.warn("Cube purge {} (business {}) attempt {} of {} failed; retrying in {}: {}", request.id(),
                request.businessId(), request.attempts(), settings.maxAttempts(), settings.retryDelay(), error);
    }

    private void finish(long id, String status, String error) {
        jdbc.update("""
                UPDATE cube_purge_requests
                SET status = :status, locked_until = NULL, finished_at = now(), last_error = COALESCE(:error, last_error)
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("status", status).addValue("error", error));
    }

    private static void pause() {
        try {
            Thread.sleep(PAUSE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        }
    }

    private static String describe(Exception e) {
        String text = (e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage())).replaceAll("\\s+", " ");
        return text.length() > MAX_ERROR ? text.substring(0, MAX_ERROR) : text;
    }
}
