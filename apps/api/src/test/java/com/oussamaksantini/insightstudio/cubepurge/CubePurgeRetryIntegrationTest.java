package com.oussamaksantini.insightstudio.cubepurge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.analytics.CubeAnswer;
import com.oussamaksantini.insightstudio.analytics.CubeClient;
import com.oussamaksantini.insightstudio.analytics.CubeClient.CacheMode;
import com.oussamaksantini.insightstudio.analytics.CubeException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Cube cleanup after a business deletion is eventual: a purge request lives in PostgreSQL, so it
 * survives Cube outages, API restarts and crashed workers, and is retried until it succeeds. Each
 * "instance" below is a fresh worker sharing only the database, as after a restart. Time passes by
 * moving the request's timestamps.
 */
class CubePurgeRetryIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NamedParameterJdbcTemplate named;

    long request;

    static final CubePurgeProperties SETTINGS = new CubePurgeProperties(true, Duration.ofSeconds(30), Duration.ofMinutes(15),
            List.of("UTC"), Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofMinutes(1), Duration.ofMinutes(4), 3);

    @BeforeEach
    void setUp() {
        new SqlFixture(jdbc).clear();
        new SqlFixture(jdbc).business("Kept Co", "kept-co", "EUR", "UTC");
        request = jdbc.queryForObject("""
                INSERT INTO cube_purge_requests (business_id, time_zone, data_version) VALUES (999, 'Pacific/Auckland', 5)
                RETURNING id
                """, Long.class);
    }

    @SuppressWarnings("unchecked")
    private CubePurgeWorker instance(CubeClient client) {
        ObjectProvider<CubeClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return new CubePurgeWorker(named, provider, SETTINGS);
    }

    private static CubeClient cubeDown() {
        CubeClient client = mock(CubeClient.class);
        when(client.send(anyLong(), anyMap(), any(CacheMode.class), any(Duration.class)))
                .thenThrow(new CubeException("Cube is unreachable", false));
        return client;
    }

    /** A Cube that answers every rollup from a data version far ahead of the request's. */
    private static CubeClient cubeUp() {
        CubeClient client = mock(CubeClient.class);
        Map<String, Object> row = Map.of("orders.data_version", "1000000", "order_categories.data_version", "1000000",
                "order_products.data_version", "1000000");
        when(client.send(anyLong(), anyMap(), eq(CacheMode.MUST_REVALIDATE), any(Duration.class)))
                .thenReturn(new CubeAnswer(false, List.of(row), List.of()));
        return client;
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("""
                SELECT status, attempts, last_error, locked_until, finished_at,
                       round(extract(epoch FROM next_attempt_at - now())) AS wait_seconds
                FROM cube_purge_requests WHERE id = ?
                """, request);
    }

    private void timePasses() {
        jdbc.update("UPDATE cube_purge_requests SET next_attempt_at = now() WHERE id = ?", request);
    }

    @Test
    void failedAttemptsArePersistedAndRetriedWithCappedBackoffNeverGivingUp() {
        CubePurgeWorker worker = instance(cubeDown());
        long[] expectedWaits = {60, 120, 240, 240, 240, 240, 240, 240};
        for (int attempt = 1; attempt <= expectedWaits.length; attempt++) {
            assertThat(worker.processDue()).isEqualTo(1);
            Map<String, Object> row = row();
            assertThat(row.get("status")).as("attempt %d", attempt).isEqualTo("PENDING");
            assertThat(row.get("attempts")).isEqualTo(attempt);
            assertThat((String) row.get("last_error")).contains("Cube is unreachable");
            assertThat(row.get("locked_until")).isNull();
            assertThat(((Number) row.get("wait_seconds")).longValue()).isBetween(expectedWaits[attempt - 1] - 5, expectedWaits[attempt - 1]);
            // Not due yet: nothing happens.
            assertThat(worker.processDue()).isZero();
            timePasses();
        }
    }

    @Test
    void aRestartedInstanceFinishesAPurgeThatFailedBefore() {
        assertThat(instance(cubeDown()).processDue()).isEqualTo(1);
        // A restart before the retry is due changes nothing: the wait is in the database.
        assertThat(instance(cubeDown()).processDue()).isZero();
        timePasses();
        assertThat(instance(cubeDown()).processDue()).isEqualTo(1);
        assertThat(row().get("attempts")).isEqualTo(2);

        // Cube is back; a new instance (after a restart) picks the request up from the database.
        timePasses();
        long versionBefore = jdbc.queryForObject("SELECT version FROM report_data_version WHERE id = 1", Long.class);
        CubePurgeWorker restarted = instance(cubeUp());
        assertThat(restarted.processDue()).isEqualTo(1);
        Map<String, Object> rebuilt = row();
        assertThat(rebuilt.get("status")).isEqualTo("PENDING");     // rebuilt; the sweep comes later
        assertThat(rebuilt.get("attempts")).isEqualTo(0);
        assertThat(rebuilt.get("last_error")).isNull();
        assertThat(((Number) rebuilt.get("wait_seconds")).longValue()).isBetween(3500L, 3600L);

        // The sweep delay passes (moved back in time); the sweep rebuild finishes the purge.
        jdbc.update("UPDATE cube_purge_requests SET created_at = now() - interval '2 hours', next_attempt_at = now() WHERE id = ?", request);
        assertThat(instance(cubeUp()).processDue()).isEqualTo(1);
        Map<String, Object> done = row();
        assertThat(done.get("status")).isEqualTo("DONE");
        assertThat(done.get("finished_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT version FROM report_data_version WHERE id = 1", Long.class)).isGreaterThan(versionBefore);
    }

    @Test
    void aSweepThatFailsIsRetriedToo() {
        jdbc.update("UPDATE cube_purge_requests SET created_at = now() - interval '2 hours' WHERE id = ?", request);
        instance(cubeDown()).processDue();
        assertThat(row().get("status")).isEqualTo("PENDING");
        timePasses();
        instance(cubeUp()).processDue();
        assertThat(row().get("status")).isEqualTo("DONE");
    }

    @Test
    void aPurgeClaimedByACrashedInstanceIsTakenOverWhenItsLeaseExpires() {
        // An instance claimed it and died mid-purge: attempts counted, lease still running.
        jdbc.update("UPDATE cube_purge_requests SET attempts = 1, locked_until = now() + interval '10 minutes' WHERE id = ?", request);
        CubePurgeWorker survivor = instance(cubeUp());
        assertThat(survivor.processDue()).isZero();
        jdbc.update("UPDATE cube_purge_requests SET locked_until = now() - interval '1 second' WHERE id = ?", request);
        assertThat(survivor.processDue()).isEqualTo(1);
        assertThat(row().get("attempts")).isEqualTo(0);       // rebuild phase done
        assertThat(row().get("locked_until")).isNull();
    }

    @Test
    void anInstanceWithoutCubeLeavesPurgesPendingForOneWithCube() {
        assertThat(instance(null).processDue()).isZero();
        Map<String, Object> row = row();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(instance(cubeUp()).processDue()).isEqualTo(1);
    }
}
