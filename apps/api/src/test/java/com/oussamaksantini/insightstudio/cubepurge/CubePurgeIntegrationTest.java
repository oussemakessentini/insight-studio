package com.oussamaksantini.insightstudio.cubepurge;

import static org.assertj.core.api.Assertions.assertThat;

import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.ApiInstance;
import com.oussamaksantini.insightstudio.testsupport.CubeStack;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A business deletion purges its rows from Cube Store (docs/account-management-api.md, "Cube purge"),
 * against real PostgreSQL 16, Cube Store and Cube v1.7.46 ({@link CubeStack}).
 *
 * <p>Cube runs with the settings the integrator adds to infra/compose.yaml, shortened for the test:
 * {@code CUBEJS_TOUCH_PRE_AGG_TIMEOUT} and {@code CUBEJS_DB_QUERY_TIMEOUT} (how long a superseded table
 * is kept after its last use), and the API's {@code insight.cube-purge.sweep-delay} just above them.
 * The deleted business reports in Asia/Tokyo, a zone Cube's refresh worker does not build, so its
 * rollups exist only because it queried them: the purge must rebuild that zone too.
 *
 * <p>After the purge is DONE, every table of Cube's pre-aggregation schema is listed in Cube Store and
 * none holds a row of the deleted business, while the other businesses' reports are unchanged.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CubePurgeIntegrationTest {

    private CubeStack stack;
    private ApiInstance sqlApi;
    private ApiInstance cubeApi;
    private JdbcTemplate jdbc;
    private long kept;
    private long keptTokyo;
    private long doomed;

    @BeforeAll
    void start() {
        stack = new CubeStack(Map.of(
                "CUBEJS_TOUCH_PRE_AGG_TIMEOUT", "20",
                "CUBEJS_DB_QUERY_TIMEOUT", "30s"));
        stack.startDatabase();
        // Flyway and the fixture; this instance has no Cube and must not take (and skip) purges.
        sqlApi = ApiInstance.start(stack.database(), Map.of("insight.cube-purge.enabled", false));
        jdbc = sqlApi.bean(JdbcTemplate.class);
        SqlFixture db = new SqlFixture(jdbc);
        TestAccounts accounts = new TestAccounts(jdbc);
        kept = db.business("Kept Co", "kept-co", "EUR", "Europe/Paris");
        keptTokyo = db.business("Kept Tokyo", "kept-tokyo", "JPY", "Asia/Tokyo");
        doomed = db.business("Doomed Co", "doomed-co", "EUR", "Asia/Tokyo");
        for (long business : List.of(kept, keptTokyo, doomed)) {
            long store = db.store(business, "S1", "Store", null);
            long p1 = db.product(business, "P1-" + business, "Widget", "Cat " + business, "5.00");
            long p2 = db.product(business, "P2-" + business, "Gadget", "Other " + business, "2.00");
            db.sale(store, "R1", "2026-03-31T23:30:00Z", p1, 2, "5.00", p2, 1, "2.00");
            db.sale(store, "R2", "2026-04-15T12:00:00Z", p2, 3, "2.00");
            accounts.member("owner-" + business + "@example.com", business, Role.OWNER);
        }
        stack.startCube();
        cubeApi = ApiInstance.start(stack.database(), Map.of(
                "insight.reports.engine", "cube",
                "insight.cube.url", stack.cubeUrl(),
                "insight.cube.api-secret", stack.secret(),
                "insight.reports.cube-timeout", "PT60S",
                "insight.cube-purge.poll-interval", "PT2S",
                "insight.cube-purge.time-zones", CubeStack.REFRESH_TIME_ZONES,
                // Above CUBEJS_TOUCH_PRE_AGG_TIMEOUT (20 s) and CUBEJS_DB_QUERY_TIMEOUT (30 s).
                "insight.cube-purge.sweep-delay", "PT45S"));
    }

    @AfterAll
    void stop() {
        for (ApiInstance instance : new ApiInstance[] {cubeApi, sqlApi}) {
            if (instance != null) {
                instance.close();
            }
        }
        if (stack != null) {
            stack.close();
        }
    }

    private HttpApiClient signIn(long business) throws Exception {
        HttpApiClient client = cubeApi.client();
        client.get("/api/session");
        HttpResponse<String> response = client.postJson("/api/auth/sign-in",
                "{\"email\":\"owner-%d@example.com\",\"password\":\"%s\"}".formatted(business, TestAccounts.PASSWORD));
        assertThat(response.statusCode()).isEqualTo(200);
        return client;
    }

    /** A report from the Cube engine, asked again while Cube is still building (503). */
    private String report(HttpApiClient client, long business, String path) throws Exception {
        Instant giveUp = Instant.now().plusSeconds(300);
        while (true) {
            HttpResponse<String> response = client.get(path, "X-Business-Id", Long.toString(business));
            if (response.statusCode() == 200) {
                assertThat(response.headers().firstValue("X-Report-Engine")).hasValue("cube");
                return response.body();
            }
            assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
            assertThat(Instant.now()).as("Cube answers within five minutes").isBefore(giveUp);
            Thread.sleep(2000);
        }
    }

    @Test
    void afterABusinessDeletionNoCubeStoreTableHoldsItsRows() throws Exception {
        HttpApiClient doomedOwner = signIn(doomed);
        HttpApiClient keptOwner = signIn(kept);
        HttpApiClient tokyoOwner = signIn(keptTokyo);
        String monthly = "/api/reports/monthly?from=2026-03-01&to=2026-04-30";
        String categories = "/api/reports/categories?from=2026-03-01&to=2026-04-30";
        // Every business's reports come from Cube's rollups, the doomed business's in Asia/Tokyo too.
        report(doomedOwner, doomed, monthly);
        report(doomedOwner, doomed, categories);
        String keptMonthly = report(keptOwner, kept, monthly);
        String keptCategories = report(keptOwner, kept, categories);
        String tokyoMonthly = report(tokyoOwner, keptTokyo, monthly);

        Map<String, Long> before = stack.businessRowsPerTable(doomed);
        assertThat(before).isNotEmpty();
        assertThat(before.values()).as("rollup tables holding the business before its deletion").anyMatch(n -> n > 0);

        HttpResponse<String> deleted = doomedOwner.deleteJson("/api/businesses/" + doomed,
                "{\"password\":\"%s\",\"confirmName\":\"Doomed Co\"}".formatted(TestAccounts.PASSWORD));
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);
        Map<String, Object> request = jdbc.queryForMap("SELECT * FROM cube_purge_requests");
        assertThat(request.get("time_zone")).isEqualTo("Asia/Tokyo");

        Instant giveUp = Instant.now().plus(Duration.ofMinutes(8));
        String status;
        while (!(status = jdbc.queryForObject("SELECT status FROM cube_purge_requests", String.class)).equals("DONE")) {
            assertThat(status).as("purge status (last error: %s)", jdbc.queryForObject(
                    "SELECT last_error FROM cube_purge_requests", String.class)).isEqualTo("PENDING");
            assertThat(Instant.now()).as("the purge finishes within eight minutes").isBefore(giveUp);
            Thread.sleep(2000);
        }
        // Builds drop superseded tables as they finish: give the last drops a moment.
        Map<String, Long> after = Map.of();
        Instant dropped = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(dropped)) {
            after = stack.businessRowsPerTable(doomed);
            if (after.values().stream().allMatch(n -> n == 0)) {
                break;
            }
            Thread.sleep(3000);
        }
        assertThat(after).as("every rollup table, after the purge").isNotEmpty();
        assertThat(after).as("rows of the deleted business per Cube Store table").allSatisfy((table, rows) ->
                assertThat(rows).as(table).isZero());
        assertThat(after.keySet()).as("no table from before the deletion is left").doesNotContainAnyElementsOf(before.keySet());
        // The kept businesses still have their rows, and their reports are unchanged.
        assertThat(stack.businessRowsPerTable(kept).values()).anyMatch(n -> n > 0);
        assertThat(report(keptOwner, kept, monthly)).isEqualTo(keptMonthly);
        assertThat(report(keptOwner, kept, categories)).isEqualTo(keptCategories);
        assertThat(report(tokyoOwner, keptTokyo, monthly)).isEqualTo(tokyoMonthly);
    }
}
