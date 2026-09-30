package com.oussamaksantini.insightstudio.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.CurrentBusiness;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /api/analytics/summary} against a stub Cube (JDK HttpServer on a random port) that
 * records every request. Proves the token's {@code businessId} is the business resolved by
 * {@link CurrentBusiness}, whatever the request says, and how Cube failures are reported.
 *
 * <p>Requests are made by real members through the real {@link CurrentBusiness} (no mocks): the
 * default {@code mvc} acts as a VIEWER of business A with A selected.
 */
class AnalyticsCubeIntegrationTest extends PostgresIntegrationTest {

    private static final String SECRET = "stub-cube-secret-0123456789abcdef-0123456789";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CubeTokens VERIFIER = new CubeTokens(SECRET, Clock.systemUTC());

    private static final List<Captured> requests = new CopyOnWriteArrayList<>();
    private static final AtomicInteger calls = new AtomicInteger();
    /** Status and body for the n-th call (0-based) of the current test. */
    private static volatile IntFunction<Reply> replies;
    private static final HttpServer cube = startStubCube();

    record Captured(String path, String authorization, JsonNode body) {
    }

    record Reply(int status, String body) {
    }

    @DynamicPropertySource
    static void cubeProperties(DynamicPropertyRegistry registry) {
        registry.add("insight.cube.url", () -> "http://127.0.0.1:" + cube.getAddress().getPort() + "/");
        registry.add("insight.cube.api-secret", () -> SECRET);
    }

    @AfterAll
    static void stopStubCube() {
        cube.stop(0);
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    /** Every request as {@link #viewerA} with business A selected. */
    MockMvc mvc;
    /** No default identity; each request says who it is. */
    MockMvc plain;
    TestUser viewerA;
    TestUser memberOfBoth;

    long businessA;
    long businessB;
    long storeA;
    long storeB;

    @BeforeEach
    void setUp() {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();
        businessA = db.business("Alpha Co", "alpha-co", "EUR", "Europe/Paris");
        businessB = db.business("Beta Co", "beta-co", "USD", "America/New_York");
        storeA = db.store(businessA, "A1", "Alpha One", "Paris");
        storeB = db.store(businessB, "B1", "Beta One", "Boston");
        TestAccounts accounts = new TestAccounts(jdbc);
        viewerA = accounts.member("viewer-a@example.com", businessA, Role.VIEWER);
        memberOfBoth = accounts.member("both@example.com", businessA, Role.VIEWER);
        accounts.member(memberOfBoth, businessB, Role.VIEWER);
        mvc = TestAccounts.mvc(context, viewerA, businessA);
        plain = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        requests.clear();
        calls.set(0);
        replies = n -> new Reply(200, """
                {"query":{},"data":[{"orders.revenue":"250.5","orders.count":"3","orders.units":"7"}],
                 "usedPreAggregations":{"x":{}}}""");
    }

    @Test
    void signsTheTokenForTheMembersBusinessAndIgnoresABusinessIdParameter() throws Exception {
        // A single-membership viewer of A, no header: A is resolved; the query parameter is ignored.
        plain.perform(get("/api/analytics/summary")
                        .with(TestAccounts.as(viewerA))
                        .param("from", "2026-06-01").param("to", "2026-06-02")
                        .param("businessId", String.valueOf(businessB)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.period.from").value("2026-06-01"))
                .andExpect(jsonPath("$.period.to").value("2026-06-02"))
                .andExpect(jsonPath("$.revenue").value(250.50))
                .andExpect(jsonPath("$.orders").value(3))
                .andExpect(jsonPath("$.unitsSold").value(7))
                .andExpect(jsonPath("$.averageOrderValue").value(83.50))
                .andExpect(jsonPath("$.source").value("cube"));

        assertThat(requests).hasSize(1);
        Captured request = requests.getFirst();
        assertThat(request.path()).isEqualTo("/cubejs-api/v1/load");
        assertThat(request.authorization()).startsWith("Bearer ");
        long tokenBusiness = VERIFIER.verify(request.authorization().substring("Bearer ".length()));
        assertThat(tokenBusiness).isEqualTo(businessA).isNotEqualTo(businessB);

        JsonNode query = request.body().get("query");
        assertThat(query.get("timezone").asString()).isEqualTo("Europe/Paris");
        JsonNode timeDimension = query.get("timeDimensions").get(0);
        assertThat(timeDimension.get("dimension").asString()).isEqualTo("orders.sold_at");
        assertThat(timeDimension.get("dateRange").get(0).asString()).isEqualTo("2026-06-01");
        assertThat(timeDimension.get("dateRange").get(1).asString()).isEqualTo("2026-06-02");
        JsonNode filter = query.get("filters").get(0);
        assertThat(filter.get("member").asString()).isEqualTo("orders.business_id");
        assertThat(filter.get("values").get(0).asString()).isEqualTo(String.valueOf(businessA));
        assertThat(query.get("filters")).hasSize(1);
    }

    @Test
    void aMemberOfAnotherBusinessCannotSelectBAndCubeIsNeverCalled() throws Exception {
        plain.perform(get("/api/analytics/summary")
                        .with(TestAccounts.as(viewerA))
                        .header("X-Business-Id", String.valueOf(businessB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Business not found."));
        assertThat(requests).isEmpty();
    }

    @Test
    void anonymousCallersAreRejectedWithoutCallingCube() throws Exception {
        plain.perform(get("/api/analytics/summary")).andExpect(status().isUnauthorized());
        assertThat(requests).isEmpty();
    }

    @Test
    void aMemberOfBothBusinessesGetsATokenForTheSelectedOneOnly() throws Exception {
        plain.perform(get("/api/analytics/summary").with(TestAccounts.as(memberOfBoth, businessA)))
                .andExpect(status().isOk());
        plain.perform(get("/api/analytics/summary").with(TestAccounts.as(memberOfBoth, businessB)))
                .andExpect(status().isOk());

        assertThat(requests).hasSize(2);
        assertThat(VERIFIER.verify(requests.get(0).authorization().substring("Bearer ".length()))).isEqualTo(businessA);
        assertThat(VERIFIER.verify(requests.get(1).authorization().substring("Bearer ".length()))).isEqualTo(businessB);
        assertThat(requests.get(1).body().get("query").get("timezone").asString()).isEqualTo("America/New_York");
    }

    @Test
    void passesTheStoreFilterForAStoreOfTheBusiness() throws Exception {
        mvc.perform(get("/api/analytics/summary")
                        .param("from", "2026-06-01").param("to", "2026-06-30")
                        .param("storeId", String.valueOf(storeA)))
                .andExpect(status().isOk());

        JsonNode filters = requests.getFirst().body().get("query").get("filters");
        assertThat(filters).hasSize(2);
        assertThat(filters.get(1).get("member").asString()).isEqualTo("orders.store_id");
        assertThat(filters.get(1).get("values").get(0).asString()).isEqualTo(String.valueOf(storeA));
    }

    @Test
    void refusesAStoreOfAnotherBusinessWithoutCallingCube() throws Exception {
        mvc.perform(get("/api/analytics/summary").param("storeId", String.valueOf(storeB)))
                .andExpect(status().isNotFound());

        assertThat(requests).isEmpty();
    }

    @Test
    void emptyResultsAreZero() throws Exception {
        replies = n -> new Reply(200, """
                {"data":[{"orders.revenue":null,"orders.count":null,"orders.units":null}]}""");

        mvc.perform(get("/api/analytics/summary").param("from", "2026-06-01").param("to", "2026-06-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revenue").value(0))
                .andExpect(jsonPath("$.orders").value(0))
                .andExpect(jsonPath("$.averageOrderValue").value(0));
    }

    @Test
    void pollsWhileCubeAsksToContinueWaiting() throws Exception {
        IntFunction<Reply> ok = replies;
        replies = n -> n < 2 ? new Reply(200, "{\"error\":\"Continue wait\"}") : ok.apply(n);

        mvc.perform(get("/api/analytics/summary").param("from", "2026-06-01").param("to", "2026-06-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(3));

        assertThat(requests).hasSize(3);
        for (Captured request : requests) {
            assertThat(VERIFIER.verify(request.authorization().substring("Bearer ".length()))).isEqualTo(businessA);
        }
    }

    @Test
    void cubeServerErrorsBecomeA502WithoutInternals() throws Exception {
        replies = n -> new Reply(500, "{\"error\":\"Error: relation \\\"secret_table\\\" does not exist at pg.internal:5432\"}");

        mvc.perform(get("/api/analytics/summary"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Analytics is temporarily unavailable."))
                .andExpect(content().string(not(containsString("secret_table"))))
                .andExpect(content().string(not(containsString("5432"))));
    }

    @Test
    void cubeRejectingTheTokenBecomesA502() throws Exception {
        replies = n -> new Reply(403, "{\"error\":\"Invalid token\"}");

        mvc.perform(get("/api/analytics/summary"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Analytics is temporarily unavailable."))
                .andExpect(content().string(not(containsString("Invalid token"))));
    }

    @Test
    void cubeQueryErrorsBecomeA502() throws Exception {
        replies = n -> new Reply(400, "{\"error\":\"Cube 'orders' cannot be scoped to a business\"}");

        mvc.perform(get("/api/analytics/summary"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(not(containsString("scoped"))));
    }

    @Test
    void malformedCubeResponsesBecomeA502() throws Exception {
        replies = n -> new Reply(200, "{\"data\":[{\"orders.revenue\":\"not-a-number\"}]}");
        mvc.perform(get("/api/analytics/summary")).andExpect(status().isBadGateway());

        replies = n -> new Reply(200, "{\"something\":\"else\"}");
        mvc.perform(get("/api/analytics/summary")).andExpect(status().isBadGateway());

        replies = n -> new Reply(200, "not json");
        mvc.perform(get("/api/analytics/summary")).andExpect(status().isBadGateway());
    }

    private static HttpServer startStubCube() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", AnalyticsCubeIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Captured(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    JSON.readTree(body)));
            Reply reply = replies.apply(calls.getAndIncrement());
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
