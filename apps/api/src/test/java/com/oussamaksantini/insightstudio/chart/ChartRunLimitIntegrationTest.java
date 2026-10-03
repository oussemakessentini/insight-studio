package com.oussamaksantini.insightstudio.chart;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.ApiInstance;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The per-business limit of chart runs in progress (docs/dashboards-contract.md §4), on an instance
 * started with a limit of 1. As in {@code ChartStatementTimeoutIntegrationTest}, another connection
 * holds an exclusive lock on {@code sale_items}, so a run stays in progress until the lock is released;
 * no test hook in the API.
 */
class ChartRunLimitIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    JdbcConnectionDetails database;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    private static final String CHART = """
            {"title": "%s", "visualization": "bar", "metrics": ["revenue"], "groupBy": "category",
             "range": {"type": "fixed", "from": "2026-01-01", "to": "2026-12-31"}}
            """;

    private static HttpApiClient signIn(ApiInstance api, String email) throws Exception {
        HttpApiClient client = api.client();
        client.get("/api/session");
        HttpResponse<String> signedIn = client.postJson("/api/auth/sign-in",
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, TestAccounts.PASSWORD));
        assertThat(signedIn.statusCode()).as(signedIn.body()).isEqualTo(200);
        return client;
    }

    private static long createChart(HttpApiClient client, String title) throws Exception {
        HttpResponse<String> created = client.postJson("/api/charts", CHART.formatted(title));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return ((Number) JsonPath.read(created.body(), "$.id")).longValue();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(15);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("waited too long").isBefore(deadline);
            Thread.sleep(20);
        }
    }

    @Test
    void runsOverTheLimitAre429WithoutStartingAndSucceedOnceASlotIsFree() throws Exception {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();
        long businessA = db.business("Busy Co", "busy-co", "EUR", "Europe/Paris");
        long store = db.store(businessA, "S", "Store", "Paris");
        long product = db.product(businessA, "P", "Tee", "Tops", "20.00");
        db.sale(store, "R1", "2026-06-01T10:00:00Z", product, 2, "20.00");
        long businessB = db.business("Calm Co", "calm-co", "EUR", "Europe/Paris");
        TestAccounts accounts = new TestAccounts(jdbc);
        accounts.member("owner@busy.test", businessA, Role.OWNER);
        accounts.member("owner@calm.test", businessB, Role.OWNER);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (ApiInstance api = ApiInstance.start(database, Map.of(
                        "insight.charts.max-concurrent-runs-per-business", 1,
                        "insight.charts.statement-timeout", "PT30S"));
                HttpApiClient first = signIn(api, "owner@busy.test");
                HttpApiClient second = signIn(api, "owner@busy.test");
                HttpApiClient other = signIn(api, "owner@calm.test")) {
            ChartRunLimiter limiter = api.bean(ChartRunLimiter.class);
            long chartA = createChart(first, "Busy chart");
            long chartB = createChart(other, "Calm chart");
            String dataA = "/api/charts/%d/data".formatted(chartA);
            assertThat(first.get(dataA).statusCode()).isEqualTo(200);
            assertThat(limiter.running(businessA)).isZero();

            Future<HttpResponse<String>> held;
            Future<HttpResponse<String>> otherBusiness;
            try (Connection locker = dataSource.getConnection()) {
                locker.setAutoCommit(false);
                try (Statement lock = locker.createStatement()) {
                    lock.execute("LOCK TABLE sale_items IN ACCESS EXCLUSIVE MODE");
                }
                held = pool.submit(() -> first.get(dataA));
                await(() -> limiter.running(businessA) == 1);

                // The business's only slot is taken: refused at once, for data and previews alike.
                Instant started = Instant.now();
                HttpResponse<String> refused = second.get(dataA);
                assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(3));
                assertThat(refused.statusCode()).as(refused.body()).isEqualTo(429);
                assertThat(refused.headers().firstValue("Retry-After")).hasValue("1");
                assertThat(refused.headers().firstValue("Content-Type")).hasValue("application/problem+json");
                // No engine ran.
                assertThat(refused.headers().firstValue("X-Report-Engine")).isEmpty();
                assertThat((String) JsonPath.read(refused.body(), "$.detail")).isEqualTo(ChartRunLimiter.BUSY);
                HttpResponse<String> preview = second.postJson("/api/charts/preview", CHART.formatted("Preview"));
                assertThat(preview.statusCode()).isEqualTo(429);
                assertThat(preview.headers().firstValue("Retry-After")).hasValue("1");
                assertThat(limiter.running(businessA)).isEqualTo(1);

                // Another business has its own slots: its run starts (and waits for the lock too).
                otherBusiness = pool.submit(() -> other.get("/api/charts/%d/data".formatted(chartB)));
                await(() -> limiter.running(businessB) == 1);
                assertThat(held.isDone()).isFalse();
                locker.rollback();
            }

            HttpResponse<String> heldResult = held.get(30, TimeUnit.SECONDS);
            assertThat(heldResult.statusCode()).as(heldResult.body()).isEqualTo(200);
            assertThat((Double) JsonPath.read(heldResult.body(), "$.totals.revenue")).isEqualTo(40.0);
            assertThat(otherBusiness.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            await(() -> limiter.running(businessA) == 0 && limiter.running(businessB) == 0);

            // Slots are released by failed runs too.
            assertThat(second.get("/api/charts/424242/data").statusCode()).isEqualTo(404);
            assertThat(second.postJson("/api/charts/preview", "{}").statusCode()).isEqualTo(400);
            assertThat(limiter.running(businessA)).isZero();
            HttpResponse<String> again = second.get(dataA);
            assertThat(again.statusCode()).isEqualTo(200);
            assertThat(second.postJson("/api/charts/preview", CHART.formatted("Preview")).statusCode()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
    }
}
