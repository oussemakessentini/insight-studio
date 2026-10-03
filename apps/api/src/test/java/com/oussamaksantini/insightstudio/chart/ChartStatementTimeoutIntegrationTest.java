package com.oussamaksantini.insightstudio.chart;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.report.CubeFreshness;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The execution-time limit of chart queries (docs/chart-builder-contract.md §3): a query that runs
 * longer than {@code insight.charts.statement-timeout} is cancelled by PostgreSQL and the API answers
 * 503 with the report engine's message, instead of holding a connection.
 *
 * <p>No test hook in the API: another connection holds an exclusive lock on {@code sale_items}, so the
 * chart's statement waits (statement_timeout counts the wait) on an instance started with a one-second
 * timeout. Locks and timeouts are ordinary PostgreSQL behaviour.
 */
class ChartStatementTimeoutIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    JdbcConnectionDetails database;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    private static final String CHART = """
            {"title": "Slow", "visualization": "bar", "metrics": ["revenue"], "groupBy": "category",
             "range": {"type": "fixed", "from": "2026-01-01", "to": "2026-12-31"}}
            """;

    @Test
    void aChartQueryCancelledByTheStatementTimeoutIsA503() throws Exception {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();
        long business = db.business("Slow Co", "slow-co", "EUR", "Europe/Paris");
        long store = db.store(business, "S", "Store", "Paris");
        long product = db.product(business, "P", "Tee", "Tops", "20.00");
        db.sale(store, "R1", "2026-06-01T10:00:00Z", product, 2, "20.00");
        new TestAccounts(jdbc).member("owner@slow.test", business, Role.OWNER);

        try (ApiInstance api = ApiInstance.start(database, Map.of("insight.charts.statement-timeout", "PT1S"));
                HttpApiClient client = api.client()) {
            client.get("/api/session");
            HttpResponse<String> signedIn = client.postJson("/api/auth/sign-in",
                    "{\"email\":\"owner@slow.test\",\"password\":\"%s\"}".formatted(TestAccounts.PASSWORD));
            assertThat(signedIn.statusCode()).as(signedIn.body()).isEqualTo(200);
            assertThat(client.postJson("/api/charts/preview", CHART).statusCode()).isEqualTo(200);

            try (Connection locker = dataSource.getConnection()) {
                locker.setAutoCommit(false);
                try (Statement lock = locker.createStatement()) {
                    lock.execute("LOCK TABLE sale_items IN ACCESS EXCLUSIVE MODE");
                }
                Instant started = Instant.now();
                HttpResponse<String> slow = client.postJson("/api/charts/preview", CHART);
                Duration took = Duration.between(started, Instant.now());
                locker.rollback();

                assertThat(slow.statusCode()).as(slow.body()).isEqualTo(503);
                assertThat(slow.headers().firstValue("Retry-After")).hasValue("30");
                assertThat(slow.headers().firstValue("Content-Type")).hasValue("application/problem+json");
                assertThat(slow.headers().firstValue("X-Report-Engine")).hasValue("sql");
                assertThat((String) JsonPath.read(slow.body(), "$.detail")).isEqualTo(CubeFreshness.UNAVAILABLE);
                assertThat(slow.body()).doesNotContain("statement", "cancel", "sale_items");
                assertThat(took).isBetween(Duration.ofMillis(900), Duration.ofSeconds(8));
            }

            // Without the lock, the same chart answers again (the connection went back to the pool clean).
            HttpResponse<String> fast = client.postJson("/api/charts/preview", CHART);
            assertThat(fast.statusCode()).isEqualTo(200);
            assertThat((Double) JsonPath.read(fast.body(), "$.totals.revenue")).isEqualTo(40.0);
        }
    }
}
