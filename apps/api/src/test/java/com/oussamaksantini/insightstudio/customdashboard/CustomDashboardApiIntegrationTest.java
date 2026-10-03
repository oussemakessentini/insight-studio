package com.oussamaksantini.insightstudio.customdashboard;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.BUSINESS_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.account.EmailVerificationService;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Custom dashboards, their layouts, revisions, concurrency, missing charts and the permission and
 * isolation rules (docs/dashboards-contract.md), against PostgreSQL. Every layout rule is also checked
 * without a database in {@code DashboardLayoutValidatorTest}.
 *
 * <p>Business A "Test Co" with charts "Revenue by store" and "Orders by store"; business B "Other Co"
 * whose chart and dashboard must never be usable or visible from A.
 */
class CustomDashboardApiIntegrationTest extends PostgresIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    WebApplicationContext context;

    /** Anonymous requests (no session). */
    @Autowired
    MockMvc anonymous;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CustomDashboardQueries queries;

    @Autowired
    PlatformTransactionManager transactions;

    SqlFixture db;
    TestAccounts accounts;
    long businessA;
    long businessB;
    TestUser ownerA;
    TestUser adminA;
    TestUser viewerA;
    TestUser unverifiedAdminA;
    TestUser ownerB;
    long revenueChart;
    long ordersChart;
    long chartB;

    @BeforeEach
    void loadFixture() throws Exception {
        db = new SqlFixture(jdbc);
        db.clear();
        accounts = new TestAccounts(jdbc);

        businessA = db.business("Test Co", "test-co", "EUR", "Europe/Paris");
        long store = db.store(businessA, "A", "Alpha", "Paris");
        long product = db.product(businessA, "P1", "Jacket", "Outerwear", "50.00");
        db.sale(store, "S1", "2026-04-20T10:00:00Z", product, 1, "50.00");
        businessB = db.business("Other Co", "other-co", "USD", "UTC");
        db.store(businessB, "X", "Other Store", null);

        ownerA = accounts.member("owner-a@example.com", businessA, Role.OWNER);
        adminA = accounts.member("admin-a@example.com", businessA, Role.ADMIN);
        viewerA = accounts.member("viewer-a@example.com", businessA, Role.VIEWER);
        unverifiedAdminA = accounts.unverifiedUser("unverified-a@example.com");
        accounts.member(unverifiedAdminA, businessA, Role.ADMIN);
        ownerB = accounts.member("owner-b@example.com", businessB, Role.OWNER);

        MockMvc owner = as(ownerA, businessA);
        revenueChart = createChart(owner, "Revenue by store", "revenue");
        ordersChart = createChart(owner, "Orders by store", "orders");
        chartB = createChart(as(ownerB, businessB), "Secret chart of B", "revenue");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private MockMvc as(TestUser user, long businessId) {
        return TestAccounts.mvc(context, user, businessId);
    }

    private static String chart(String title, String metric) {
        return """
                {"schemaVersion": 1, "title": "%s", "visualization": "bar", "metrics": ["%s"], "groupBy": "store",
                 "granularity": null, "range": {"type": "fixed", "from": "2026-04-01", "to": "2026-05-31"},
                 "filters": {}, "limit": 10, "engine": "sql"}
                """.formatted(title, metric);
    }

    private static long createChart(MockMvc mvc, String title, String metric) throws Exception {
        String json = body(mvc.perform(postJson("/api/charts", chart(title, metric))).andExpect(status().isCreated()).andReturn());
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    /** Two widgets side by side on desktop and stacked on mobile (the contract's example). */
    static String layout(long first, long second) {
        return """
                {"schemaVersion": 1,
                 "widgets": [{"id": "w-1a2b", "chartId": %d}, {"id": "w-9f3c", "chartId": %d}],
                 "desktop": {"columns": 12, "items": [{"id": "w-1a2b", "x": 0, "y": 0, "w": 6, "h": 4},
                                                      {"id": "w-9f3c", "x": 6, "y": 0, "w": 6, "h": 4}]},
                 "mobile": {"columns": 4, "items": [{"id": "w-1a2b", "x": 0, "y": 0, "w": 4, "h": 4},
                                                    {"id": "w-9f3c", "x": 0, "y": 4, "w": 4, "h": 4}]}}
                """.formatted(first, second);
    }

    /** One widget, full width. */
    static String layout(long chart) {
        return """
                {"schemaVersion": 1, "widgets": [{"id": "only", "chartId": %d}],
                 "desktop": {"columns": 12, "items": [{"id": "only", "x": 0, "y": 0, "w": 12, "h": 6}]},
                 "mobile": {"columns": 4, "items": [{"id": "only", "x": 0, "y": 0, "w": 4, "h": 6}]}}
                """.formatted(chart);
    }

    static final String EMPTY_LAYOUT =
            "{\"schemaVersion\":1,\"widgets\":[],\"desktop\":{\"columns\":12,\"items\":[]},\"mobile\":{\"columns\":4,\"items\":[]}}";

    /** The compact form the API stores and returns. */
    private static String normalized(String layout) {
        return JSON.readTree(layout).toString();
    }

    private static String createBody(String name, String layout) {
        return "{\"name\": \"%s\", \"layout\": %s}".formatted(name, layout);
    }

    private static String saveBody(String name, String layout, int expectedRevision) {
        return "{\"name\": \"%s\", \"layout\": %s, \"expectedRevision\": %d}".formatted(name, layout, expectedRevision);
    }

    private static MockHttpServletRequestBuilder postJson(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder putJson(long id, String body) {
        return put("/api/dashboards/" + id).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long create(MockMvc mvc, String name, String layout) throws Exception {
        String json = body(mvc.perform(postJson("/api/dashboards", createBody(name, layout)))
                .andExpect(status().isCreated()).andReturn());
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String layoutOf(MvcResult result) throws Exception {
        return JSON.readTree(body(result)).get("layout").toString();
    }

    private long refs(long dashboardId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dashboard_chart_refs WHERE dashboard_id = ?", Long.class, dashboardId);
    }

    private static List<String> readPaths(long id, long chartId) {
        String base = "/api/dashboards/" + id;
        return List.of("/api/dashboards", base, base + "?revision=1", base + "/revisions",
                "/api/charts/" + chartId + "/dashboards");
    }

    private static List<RequestBuilder> writes(long id, String layout) {
        return List.of(
                postJson("/api/dashboards", createBody("New one", layout)),
                putJson(id, saveBody("Renamed", layout, 1)),
                postJson("/api/dashboards/" + id + "/duplicate", "{}"),
                delete("/api/dashboards/" + id));
    }

    // --- layouts and revisions -------------------------------------------------------------------

    @Nested
    class Persistence {

        @Test
        void aNewDashboardWithoutALayoutIsEmpty() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            MvcResult created = owner.perform(postJson("/api/dashboards", "{\"name\": \"  Weekly review  \"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Weekly review"))
                    .andExpect(jsonPath("$.revision").value(1))
                    .andExpect(jsonPath("$.widgets", hasSize(0)))
                    .andExpect(jsonPath("$.createdBy").value("owner-a"))
                    .andExpect(jsonPath("$.updatedBy").value("owner-a"))
                    .andExpect(jsonPath("$.createdAt").isString())
                    .andExpect(jsonPath("$.updatedAt").isString())
                    .andReturn();
            assertThat(layoutOf(created)).isEqualTo(EMPTY_LAYOUT);
            owner.perform(postJson("/api/dashboards", "{\"name\": \"Null layout\", \"layout\": null}"))
                    .andExpect(status().isCreated());
            assertThat(db.count("dashboard_revisions")).isEqualTo(2);
            assertThat(db.count("dashboard_chart_refs")).isZero();
        }

        @Test
        void savedLayoutsReloadAsTheSameNormalizedJson() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            // Sent with spaces, without schemaVersion: stored and returned normalized.
            String sent = layout(revenueChart, ordersChart).replace("\"schemaVersion\": 1,", "");
            String expected = normalized(layout(revenueChart, ordersChart));
            MvcResult created = owner.perform(postJson("/api/dashboards", createBody("Sales", sent)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.widgets", hasSize(2)))
                    .andExpect(jsonPath("$.widgets[0].id").value("w-1a2b"))
                    .andExpect(jsonPath("$.widgets[0].chartId").value(revenueChart))
                    .andExpect(jsonPath("$.widgets[0].missing").value(false))
                    .andExpect(jsonPath("$.widgets[0].chart.id").value(revenueChart))
                    .andExpect(jsonPath("$.widgets[0].chart.title").value("Revenue by store"))
                    .andExpect(jsonPath("$.widgets[0].chart.visualization").value("bar"))
                    .andExpect(jsonPath("$.widgets[0].chart.revision").value(1))
                    .andExpect(jsonPath("$.widgets[1].chart.title").value("Orders by store"))
                    .andReturn();
            assertThat(layoutOf(created)).isEqualTo(expected);
            long id = ((Number) JsonPath.read(body(created), "$.id")).longValue();
            assertThat(layoutOf(owner.perform(get("/api/dashboards/" + id)).andExpect(status().isOk()).andReturn()))
                    .isEqualTo(expected);
            // The same JSON in the database (jsonb keeps its own key order; the API answers in the contract's).
            assertThat(JSON.readTree(jdbc.queryForObject("SELECT layout::text FROM dashboard_revisions", String.class)))
                    .isEqualTo(JSON.readTree(expected));
            assertThat(jdbc.queryForObject("SELECT schema_version FROM dashboard_revisions", Integer.class)).isEqualTo(1);
            assertThat(refs(id)).isEqualTo(2);

            // A second revision: the same chart twice, moved and resized, by another admin.
            String moved = """
                    {"schemaVersion": 1,
                     "widgets": [{"id": "w-9f3c", "chartId": %d}, {"id": "w-1a2b", "chartId": %d}, {"id": "w-3", "chartId": %d}],
                     "desktop": {"columns": 12, "items": [{"id": "w-9f3c", "x": 0, "y": 0, "w": 12, "h": 3},
                                                          {"id": "w-1a2b", "x": 0, "y": 3, "w": 4, "h": 5},
                                                          {"id": "w-3", "x": 4, "y": 3, "w": 8, "h": 5}]},
                     "mobile": {"columns": 4, "items": [{"id": "w-9f3c", "x": 0, "y": 0, "w": 4, "h": 3},
                                                        {"id": "w-1a2b", "x": 0, "y": 3, "w": 2, "h": 2},
                                                        {"id": "w-3", "x": 2, "y": 3, "w": 2, "h": 2}]}}
                    """.formatted(ordersChart, revenueChart, revenueChart);
            MvcResult saved = as(adminA, businessA).perform(putJson(id, saveBody("Sales", moved, 1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.revision").value(2))
                    .andExpect(jsonPath("$.createdBy").value("owner-a"))
                    .andExpect(jsonPath("$.updatedBy").value("admin-a"))
                    .andExpect(jsonPath("$.widgets[*].id", contains("w-9f3c", "w-1a2b", "w-3")))
                    .andExpect(jsonPath("$.widgets[2].chart.title").value("Revenue by store"))
                    .andReturn();
            assertThat(layoutOf(saved)).isEqualTo(normalized(moved));
            assertThat(layoutOf(owner.perform(get("/api/dashboards/" + id)).andReturn())).isEqualTo(normalized(moved));
            assertThat(refs(id)).isEqualTo(2); // distinct charts

            // The first revision stays readable.
            MvcResult first = owner.perform(get("/api/dashboards/" + id + "?revision=1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.revision").value(1))
                    .andExpect(jsonPath("$.name").value("Sales"))
                    .andExpect(jsonPath("$.widgets", hasSize(2)))
                    .andExpect(jsonPath("$.createdBy").value("owner-a"))
                    .andExpect(jsonPath("$.updatedBy").value("owner-a"))
                    .andReturn();
            assertThat(layoutOf(first)).isEqualTo(expected);
            owner.perform(get("/api/dashboards/" + id + "/revisions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].revision", contains(2, 1)))
                    .andExpect(jsonPath("$[*].name", contains("Sales", "Sales")))
                    .andExpect(jsonPath("$[*].widgetCount", contains(3, 2)))
                    .andExpect(jsonPath("$[*].createdBy", contains("admin-a", "owner-a")))
                    .andExpect(jsonPath("$[0].createdAt").isString())
                    .andExpect(jsonPath("$[0].layout").doesNotExist());
            owner.perform(get("/api/dashboards/" + id + "?revision=3"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Revision 3 of this dashboard was not found."));
        }

        @Test
        void aRenameIsASaveThatBumpsTheRevision() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, "Draft", layout(revenueChart));
            owner.perform(putJson(id, saveBody("  Final  ", layout(revenueChart), 1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Final"))
                    .andExpect(jsonPath("$.revision").value(2));
            owner.perform(get("/api/dashboards/" + id + "/revisions")).andExpect(jsonPath("$[*].name", contains("Final", "Draft")));
            owner.perform(get("/api/dashboards/" + id + "?revision=1")).andExpect(jsonPath("$.name").value("Draft"));
            owner.perform(get("/api/dashboards")).andExpect(jsonPath("$[0].name").value("Final"))
                    .andExpect(jsonPath("$[0].revision").value(2));
        }

        @Test
        void theListIsByNameWithWidgetAndMissingCounts() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            create(owner, "beta", layout(revenueChart, ordersChart));
            long alpha = create(owner, "Alpha", layout(ordersChart));
            create(owner, "gamma", EMPTY_LAYOUT);
            owner.perform(get("/api/dashboards"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].name", contains("Alpha", "beta", "gamma")))
                    .andExpect(jsonPath("$[*].widgetCount", contains(1, 2, 0)))
                    .andExpect(jsonPath("$[*].missingCount", contains(0, 0, 0)))
                    .andExpect(jsonPath("$[0].id").value(alpha))
                    .andExpect(jsonPath("$[0].revision").value(1))
                    .andExpect(jsonPath("$[0].updatedBy").value("owner-a"))
                    .andExpect(jsonPath("$[0].updatedAt").isString())
                    .andExpect(jsonPath("$[0].layout").doesNotExist());
        }

        @Test
        void namesAreUniquePerBusinessIgnoringCase() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long first = create(owner, "Weekly", EMPTY_LAYOUT);
            owner.perform(postJson("/api/dashboards", createBody("WEEKLY", EMPTY_LAYOUT)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("A dashboard named 'WEEKLY' already exists."));
            long second = create(owner, "Monthly", EMPTY_LAYOUT);
            owner.perform(putJson(second, saveBody("weekly", EMPTY_LAYOUT, 1))).andExpect(status().isConflict());
            // Its own name in another case is fine; another business may use the name.
            owner.perform(putJson(first, saveBody("WeeKly", EMPTY_LAYOUT, 1))).andExpect(status().isOk());
            create(as(ownerB, businessB), "Weekly", EMPTY_LAYOUT);
            assertThat(db.count("dashboard_revisions")).isEqualTo(4);
        }

        @Test
        void aBusinessHasAtMost50Dashboards() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, "Dashboard 1", EMPTY_LAYOUT);
            jdbc.update("""
                    INSERT INTO dashboards (business_id, name, created_by, updated_by)
                    SELECT ?, 'Filler ' || n, ?, ? FROM generate_series(2, 50) AS n
                    """, businessA, ownerA.id(), ownerA.id());
            owner.perform(postJson("/api/dashboards", createBody("Dashboard 51", EMPTY_LAYOUT)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("A business can have at most 50 dashboards. Delete one before adding another."));
            owner.perform(post("/api/dashboards/" + id + "/duplicate")).andExpect(status().isConflict());
            // Saving an existing dashboard is not a new one; another business is not affected.
            owner.perform(putJson(id, saveBody("Dashboard 1", EMPTY_LAYOUT, 1))).andExpect(status().isOk());
            create(as(ownerB, businessB), "B's", EMPTY_LAYOUT);
            owner.perform(delete("/api/dashboards/" + id)).andExpect(status().isNoContent());
            create(owner, "Dashboard 51", EMPTY_LAYOUT);
        }

        @Test
        void deleteRemovesRevisionsAndReferencesButNotTheCharts() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, "Short-lived", layout(revenueChart, ordersChart));
            owner.perform(putJson(id, saveBody("Short-lived", layout(revenueChart, ordersChart), 1))).andExpect(status().isOk());
            long kept = create(owner, "Kept", layout(revenueChart));
            owner.perform(delete("/api/dashboards/" + id)).andExpect(status().isNoContent());

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dashboard_revisions WHERE dashboard_id = ?", Long.class, id)).isZero();
            assertThat(refs(id)).isZero();
            assertThat(db.count("chart_definitions")).isEqualTo(3);
            for (String path : List.of("/api/dashboards/" + id, "/api/dashboards/" + id + "?revision=1",
                    "/api/dashboards/" + id + "/revisions")) {
                owner.perform(get(path)).andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.detail").value("Dashboard not found."));
            }
            owner.perform(delete("/api/dashboards/" + id)).andExpect(status().isNotFound());
            owner.perform(get("/api/dashboards/" + kept)).andExpect(status().isOk());
        }

        @Test
        void unknownIdsAreNotFoundAndMalformedOnesBadRequests() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            for (String path : List.of("/api/dashboards/424242", "/api/dashboards/424242/revisions")) {
                owner.perform(get(path)).andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.detail").value("Dashboard not found."));
            }
            owner.perform(putJson(424242, saveBody("x", EMPTY_LAYOUT, 1))).andExpect(status().isNotFound());
            owner.perform(post("/api/dashboards/424242/duplicate")).andExpect(status().isNotFound());
            owner.perform(get("/api/dashboards/abc")).andExpect(status().isBadRequest());
            owner.perform(get("/api/dashboards/1?revision=x")).andExpect(status().isBadRequest());
            owner.perform(get("/api/charts/424242/dashboards")).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Chart not found."));
        }
    }

    // --- validation over HTTP --------------------------------------------------------------------

    @Nested
    class Validation {

        /** A 400 whose errors are on {@code fields}, the first one with {@code message}. */
        private void refused(String layout, String message, String... fields) throws Exception {
            MockMvc owner = as(ownerA, businessA);
            owner.perform(postJson("/api/dashboards", createBody("Invalid", layout)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.errors[*].field", contains(fields)))
                    .andExpect(jsonPath("$.errors[0].message").value(message));
        }

        @Test
        void everyLayoutRuleIsAFieldLevel400() throws Exception {
            String valid = layout(revenueChart, ordersChart);
            refused(valid.replace("\"x\": 6, \"y\": 0, \"w\": 6", "\"x\": 5, \"y\": 1, \"w\": 6"),
                    "w-9f3c overlaps w-1a2b (layout.desktop.items[0]).", "layout.desktop.items[1]");
            refused(valid.replace("\"x\": 6, \"y\": 0, \"w\": 6", "\"x\": 7, \"y\": 0, \"w\": 6"),
                    "The item is wider than the grid: x + w must be at most 12.", "layout.desktop.items[1].w");
            refused(valid.replace("\"x\": 0, \"y\": 4, \"w\": 4, \"h\": 4", "\"x\": 0, \"y\": 4, \"w\": 4, \"h\": 1"),
                    "'h' must be a whole number from 2 to 12.", "layout.mobile.items[1].h");
            refused(valid.replace("{\"id\": \"w-9f3c\", \"x\": 0, \"y\": 4", "{\"id\": \"w-1a2b\", \"x\": 0, \"y\": 4"),
                    "Widget 'w-1a2b' is placed twice in the mobile grid.", "layout.mobile.items[1].id", "layout.mobile.items");
            refused(valid.replace("\"widgets\": [{\"id\": \"w-1a2b\", \"chartId\": %d}, {\"id\": \"w-9f3c\"".formatted(revenueChart),
                    "\"widgets\": [{\"id\": \"w-1a2b\", \"chartId\": %d}, {\"id\": \"w-1a2b\"".formatted(revenueChart)),
                    "Widget id 'w-1a2b' is used twice.", "layout.widgets[1].id", "layout.desktop.items[1].id", "layout.mobile.items[1].id");
            refused(valid.replace("\"chartId\": " + revenueChart, "\"chartId\": %d, \"title\": \"Mine\"".formatted(revenueChart)),
                    "Unknown field 'title'.", "layout.widgets[0].title");
            refused(valid.replace("\"columns\": 4", "\"columns\": 6"),
                    "'columns' must be 4 for the mobile grid.", "layout.mobile.columns");
            refused(valid.replace("\"chartId\": " + ordersChart, "\"chartId\": 987654"),
                    "Chart 987654 is not a chart of this business.", "layout.widgets[1].chartId");
            refused(valid.replace("\"chartId\": " + ordersChart, "\"chartId\": " + chartB),
                    "Chart %d is not a chart of this business.".formatted(chartB), "layout.widgets[1].chartId");
            assertThat(db.count("dashboards")).isZero();
        }

        @Test
        void aWidgetMissingFromAGridIsRefused() throws Exception {
            String desktopOnly = """
                    {"widgets": [{"id": "a", "chartId": %d}, {"id": "b", "chartId": %d}],
                     "desktop": {"columns": 12, "items": [{"id": "a", "x": 0, "y": 0, "w": 6, "h": 4},
                                                          {"id": "b", "x": 6, "y": 0, "w": 6, "h": 4}]},
                     "mobile": {"columns": 4, "items": [{"id": "a", "x": 0, "y": 0, "w": 4, "h": 4}]}}
                    """.formatted(revenueChart, ordersChart);
            refused(desktopOnly, "Widget 'b' is not placed in the mobile grid.", "layout.mobile.items");
        }

        @Test
        void atMost24Widgets() throws Exception {
            String widgets = IntStream.range(0, 25).mapToObj(i -> "{\"id\": \"w%d\", \"chartId\": %d}".formatted(i, revenueChart))
                    .collect(Collectors.joining(","));
            String items = IntStream.range(0, 25).mapToObj(i -> "{\"id\": \"w%d\", \"x\": 0, \"y\": %d, \"w\": 4, \"h\": 2}".formatted(i, i * 2))
                    .collect(Collectors.joining(","));
            refused("{\"widgets\": [%s], \"desktop\": {\"columns\": 12, \"items\": [%s]}, \"mobile\": {\"columns\": 4, \"items\": [%s]}}"
                    .formatted(widgets, items, items), "A dashboard can have at most 24 widgets.", "layout.widgets");
        }

        @Test
        void namesBodiesAndExpectedRevisionsAreChecked() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            owner.perform(postJson("/api/dashboards", "{\"name\": \"  \", \"colour\": \"red\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[*].field", contains("colour", "name")))
                    .andExpect(jsonPath("$.detail").value("Unknown field 'colour'. Enter a name for the dashboard."));
            owner.perform(postJson("/api/dashboards", createBody("N".repeat(121), EMPTY_LAYOUT)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].message").value("The name may be at most 120 characters."));
            owner.perform(postJson("/api/dashboards", "[]")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("name"));
            owner.perform(post("/api/dashboards")).andExpect(status().isBadRequest());
            owner.perform(postJson("/api/dashboards", "{not json")).andExpect(status().isBadRequest());

            long id = create(owner, "Board", layout(revenueChart));
            owner.perform(putJson(id, "{\"name\": \"Board\", \"layout\": %s}".formatted(layout(revenueChart))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[*].field", contains("expectedRevision")));
            owner.perform(putJson(id, "{\"name\": \"Board\", \"expectedRevision\": 1}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[*].field", contains("layout")));
            owner.perform(putJson(id, saveBody("Board", layout(chartB), 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("layout.widgets[0].chartId"));
            owner.perform(postJson("/api/dashboards/" + id + "/duplicate", "{\"name\": \"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("name"));
            owner.perform(postJson("/api/dashboards/" + id + "/duplicate", "{\"layout\": {}}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("layout"));
            assertThat(db.count("dashboards")).isEqualTo(1);
            assertThat(db.count("dashboard_revisions")).isEqualTo(1);
        }
    }

    // --- duplication -----------------------------------------------------------------------------

    @Nested
    class Duplication {

        @Test
        void aCopyHasTheCurrentLayoutRevisionOneAndAUniqueName() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, "Sales", layout(revenueChart));
            owner.perform(putJson(id, saveBody("Sales", layout(revenueChart, ordersChart), 1))).andExpect(status().isOk());

            MvcResult copy = owner.perform(postJson("/api/dashboards/" + id + "/duplicate", "{}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Copy of Sales"))
                    .andExpect(jsonPath("$.revision").value(1))
                    .andExpect(jsonPath("$.widgets", hasSize(2)))
                    .andReturn();
            long copyId = ((Number) JsonPath.read(body(copy), "$.id")).longValue();
            assertThat(copyId).isNotEqualTo(id);
            assertThat(layoutOf(copy)).isEqualTo(normalized(layout(revenueChart, ordersChart)));
            assertThat(refs(copyId)).isEqualTo(2);
            owner.perform(get("/api/dashboards/" + copyId + "/revisions")).andExpect(jsonPath("$[*].revision", contains(1)));

            owner.perform(post("/api/dashboards/" + id + "/duplicate"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Copy of Sales (2)"));
            owner.perform(postJson("/api/dashboards/" + id + "/duplicate", "{\"name\": \"Mine\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Mine"));
            owner.perform(postJson("/api/dashboards/" + id + "/duplicate", "{\"name\": \"MINE\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("A dashboard named 'MINE' already exists."));
            long longOne = create(owner, "L".repeat(120), EMPTY_LAYOUT);
            owner.perform(post("/api/dashboards/" + longOne + "/duplicate"))
                    .andExpect(jsonPath("$.name").value("Copy of " + "L".repeat(112)));
        }

        @Test
        void widgetsOfDeletedChartsAreDroppedFromTheCopy() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, "Sales", layout(revenueChart, ordersChart));
            owner.perform(delete("/api/charts/" + revenueChart)).andExpect(status().isNoContent());

            MvcResult copy = owner.perform(post("/api/dashboards/" + id + "/duplicate"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.widgets[*].id", contains("w-9f3c")))
                    .andExpect(jsonPath("$.widgets[0].missing").value(false))
                    .andReturn();
            assertThat(layoutOf(copy)).isEqualTo(normalized("""
                    {"schemaVersion": 1, "widgets": [{"id": "w-9f3c", "chartId": %d}],
                     "desktop": {"columns": 12, "items": [{"id": "w-9f3c", "x": 6, "y": 0, "w": 6, "h": 4}]},
                     "mobile": {"columns": 4, "items": [{"id": "w-9f3c", "x": 0, "y": 4, "w": 4, "h": 4}]}}
                    """.formatted(ordersChart)));
            // The source keeps its missing widget.
            owner.perform(get("/api/dashboards/" + id)).andExpect(jsonPath("$.widgets", hasSize(2)));
        }
    }

    // --- concurrent editors ----------------------------------------------------------------------

    @Nested
    class Concurrency {

        @Test
        void aSaveFromAStaleRevisionIsA409SayingWhoSaved() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, "Shared", layout(revenueChart));
            String updatedAt = JsonPath.read(body(as(adminA, businessA)
                    .perform(putJson(id, saveBody("First edit", layout(ordersChart), 1)))
                    .andExpect(status().isOk()).andReturn()), "$.updatedAt");

            // The owner also opened revision 1.
            owner.perform(putJson(id, saveBody("Second edit", layout(revenueChart, ordersChart), 1)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.detail").value(CustomDashboardService.STALE))
                    .andExpect(jsonPath("$.currentRevision").value(2))
                    .andExpect(jsonPath("$.updatedBy").value("admin-a"))
                    .andExpect(jsonPath("$.updatedAt").value(updatedAt));
            owner.perform(get("/api/dashboards/" + id))
                    .andExpect(jsonPath("$.name").value("First edit"))
                    .andExpect(jsonPath("$.revision").value(2))
                    .andExpect(jsonPath("$.widgets[*].chartId", contains((int) ordersChart)));
            assertThat(db.count("dashboard_revisions")).isEqualTo(2);
            // Reloaded, the owner saves on top of revision 2.
            owner.perform(putJson(id, saveBody("Second edit", layout(revenueChart, ordersChart), 2)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.revision").value(3));
        }

        @Test
        void ofTwoSimultaneousSavesFromTheSameRevisionExactlyOneWins() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                for (int round = 0; round < 5; round++) {
                    long id = create(owner, "Race " + round, layout(revenueChart));
                    CyclicBarrier start = new CyclicBarrier(2);
                    List<Future<MvcResult>> saves = new ArrayList<>();
                    for (TestUser editor : List.of(ownerA, adminA)) {
                        MockMvc mvc = as(editor, businessA);
                        String body = saveBody("Race %d by %s".formatted(round, editor.email()),
                                layout(revenueChart, ordersChart), 1);
                        saves.add(pool.submit(() -> {
                            start.await(10, TimeUnit.SECONDS);
                            return mvc.perform(putJson(id, body)).andReturn();
                        }));
                    }
                    List<Integer> statuses = new ArrayList<>();
                    for (Future<MvcResult> save : saves) {
                        MvcResult result = save.get(30, TimeUnit.SECONDS);
                        statuses.add(result.getResponse().getStatus());
                        if (result.getResponse().getStatus() == 409) {
                            assertThat((Integer) JsonPath.read(body(result), "$.currentRevision")).isEqualTo(2);
                        }
                    }
                    assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(200, 409);
                    assertThat(jdbc.queryForObject("SELECT current_revision FROM dashboards WHERE id = ?", Integer.class, id))
                            .isEqualTo(2);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dashboard_revisions WHERE dashboard_id = ?", Long.class, id))
                            .isEqualTo(2);
                    assertThat(refs(id)).isEqualTo(2);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // --- charts that change or disappear ---------------------------------------------------------

    @Nested
    class MissingCharts {

        @Test
        void aDeletedChartsWidgetIsReportedMissingUntilRemoved() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, "Sales", layout(revenueChart, ordersChart));
            owner.perform(get("/api/charts/" + revenueChart + "/dashboards")).andExpect(jsonPath("$[*].id", contains((int) id)));
            owner.perform(delete("/api/charts/" + revenueChart)).andExpect(status().isNoContent());
            assertThat(refs(id)).isEqualTo(1);

            owner.perform(get("/api/dashboards/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.widgets", hasSize(2)))
                    .andExpect(jsonPath("$.widgets[0].id").value("w-1a2b"))
                    .andExpect(jsonPath("$.widgets[0].chartId").value(revenueChart))
                    .andExpect(jsonPath("$.widgets[0].missing").value(true))
                    .andExpect(jsonPath("$.widgets[0].chart").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.widgets[1].missing").value(false))
                    .andExpect(jsonPath("$.widgets[1].chart.title").value("Orders by store"));
            assertThat(body(owner.perform(get("/api/dashboards/" + id)).andReturn())).contains("\"chart\":null");
            owner.perform(get("/api/dashboards"))
                    .andExpect(jsonPath("$[0].widgetCount").value(2))
                    .andExpect(jsonPath("$[0].missingCount").value(1));
            owner.perform(get("/api/charts/" + revenueChart + "/dashboards")).andExpect(status().isNotFound());

            // Saving the layout unchanged is refused: the chart is no longer one of this business.
            owner.perform(putJson(id, saveBody("Sales", layout(revenueChart, ordersChart), 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[*].field", contains("layout.widgets[0].chartId")))
                    .andExpect(jsonPath("$.errors[0].message").value("Chart %d is not a chart of this business.".formatted(revenueChart)));

            // Without the widget it saves.
            String withoutIt = """
                    {"schemaVersion": 1, "widgets": [{"id": "w-9f3c", "chartId": %d}],
                     "desktop": {"columns": 12, "items": [{"id": "w-9f3c", "x": 6, "y": 0, "w": 6, "h": 4}]},
                     "mobile": {"columns": 4, "items": [{"id": "w-9f3c", "x": 0, "y": 4, "w": 4, "h": 4}]}}
                    """.formatted(ordersChart);
            owner.perform(putJson(id, saveBody("Sales", withoutIt, 1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.revision").value(2))
                    .andExpect(jsonPath("$.widgets[*].missing", contains(false)));
            owner.perform(get("/api/dashboards")).andExpect(jsonPath("$[0].missingCount").value(0));
            // The old revision still shows the widget as missing.
            owner.perform(get("/api/dashboards/" + id + "?revision=1")).andExpect(jsonPath("$.widgets[0].missing").value(true));
        }

        @Test
        void editingAChartChangesTheWidgetsTitleAndRevision() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, "Sales", layout(revenueChart));
            owner.perform(put("/api/charts/" + revenueChart).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"definition\": %s, \"expectedRevision\": 1}".formatted(chart("Revenue (renamed)", "revenue"))))
                    .andExpect(status().isOk());
            owner.perform(get("/api/dashboards/" + id))
                    .andExpect(jsonPath("$.revision").value(1))
                    .andExpect(jsonPath("$.widgets[0].chart.title").value("Revenue (renamed)"))
                    .andExpect(jsonPath("$.widgets[0].chart.revision").value(2));
        }

        @Test
        void chartDashboardsListsTheDashboardsWhoseCurrentLayoutUsesTheChart() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long beta = create(owner, "beta", layout(revenueChart, ordersChart));
            long alpha = create(owner, "Alpha", layout(revenueChart));
            create(owner, "Orders only", layout(ordersChart));
            create(as(ownerB, businessB), "B's board", layout(chartB));

            MockMvc viewer = as(viewerA, businessA);
            viewer.perform(get("/api/charts/" + revenueChart + "/dashboards"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].id", contains((int) alpha, (int) beta)))
                    .andExpect(jsonPath("$[*].name", contains("Alpha", "beta")));
            // Removed from beta's layout: no longer listed.
            owner.perform(putJson(beta, saveBody("beta", layout(ordersChart), 1))).andExpect(status().isOk());
            viewer.perform(get("/api/charts/" + revenueChart + "/dashboards")).andExpect(jsonPath("$[*].name", contains("Alpha")));
            viewer.perform(get("/api/charts/" + ordersChart + "/dashboards"))
                    .andExpect(jsonPath("$[*].name", contains("beta", "Orders only")));
            // Another business's chart: not found, nothing about its dashboards.
            String other = body(viewer.perform(get("/api/charts/" + chartB + "/dashboards"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Chart not found.")).andReturn());
            assertThat(other).doesNotContain("B's board");
        }
    }

    // --- permissions -----------------------------------------------------------------------------

    @Nested
    class Permissions {

        @Test
        void viewersReadButCannotChangeAnything() throws Exception {
            long id = create(as(ownerA, businessA), "Shared", layout(revenueChart));
            MockMvc viewer = as(viewerA, businessA);
            for (String path : readPaths(id, revenueChart)) {
                viewer.perform(get(path)).andExpect(status().isOk());
            }
            for (RequestBuilder write : writes(id, layout(revenueChart))) {
                viewer.perform(write)
                        .andExpect(status().isForbidden())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.detail").value("You need the ADMIN role for this."));
            }
            assertThat(jdbc.queryForObject("SELECT name FROM dashboards", String.class)).isEqualTo("Shared");
            assertThat(db.count("dashboard_revisions")).isEqualTo(1);
        }

        @Test
        void ownersAndAdminsManageDashboards() throws Exception {
            for (TestUser user : List.of(ownerA, adminA)) {
                MockMvc mvc = as(user, businessA);
                long id = create(mvc, "By " + user.email(), layout(revenueChart));
                mvc.perform(putJson(id, saveBody("Renamed by " + user.email(), layout(ordersChart), 1))).andExpect(status().isOk());
                for (String path : readPaths(id, ordersChart)) {
                    mvc.perform(get(path)).andExpect(status().isOk());
                }
                String copy = body(mvc.perform(post("/api/dashboards/" + id + "/duplicate")).andExpect(status().isCreated()).andReturn());
                mvc.perform(delete("/api/dashboards/" + JsonPath.read(copy, "$.id"))).andExpect(status().isNoContent());
                mvc.perform(delete("/api/dashboards/" + id)).andExpect(status().isNoContent());
            }
            assertThat(db.count("dashboards")).isZero();
        }

        @Test
        void unverifiedAccountsReadButCannotWrite() throws Exception {
            long id = create(as(ownerA, businessA), "Shared", layout(revenueChart));
            MockMvc unverified = as(unverifiedAdminA, businessA);
            for (String path : readPaths(id, revenueChart)) {
                unverified.perform(get(path)).andExpect(status().isOk());
            }
            for (RequestBuilder write : writes(id, layout(revenueChart))) {
                unverified.perform(write)
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.detail").value(EmailVerificationService.VERIFY_FIRST));
            }
            assertThat(db.count("dashboards")).isEqualTo(1);
        }

        @Test
        void anonymousCallersAreUnauthorized() throws Exception {
            long id = create(as(ownerA, businessA), "Shared", layout(revenueChart));
            for (String path : readPaths(id, revenueChart)) {
                anonymous.perform(get(path))
                        .andExpect(status().isUnauthorized())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
                anonymous.perform(get(path).header(BUSINESS_HEADER, businessA)).andExpect(status().isUnauthorized());
            }
            for (RequestBuilder write : writes(id, layout(revenueChart))) {
                anonymous.perform(((MockHttpServletRequestBuilder) write).with(TestAccounts.csrf()))
                        .andExpect(status().isUnauthorized());
            }
            assertThat(jdbc.queryForObject("SELECT name FROM dashboards", String.class)).isEqualTo("Shared");
        }

        @Test
        void writesNeedTheCsrfHeader() throws Exception {
            long id = create(as(ownerA, businessA), "Shared", layout(revenueChart));
            List<RequestBuilder> forged = new ArrayList<>();
            for (RequestBuilder write : writes(id, layout(revenueChart))) {
                forged.add(((MockHttpServletRequestBuilder) write).with(TestAccounts.as(ownerA)).header(BUSINESS_HEADER, businessA));
            }
            for (RequestBuilder write : writes(id, layout(revenueChart))) {
                forged.add(((MockHttpServletRequestBuilder) write).with(TestAccounts.as(ownerA)).with(TestAccounts.invalidCsrf())
                        .header(BUSINESS_HEADER, businessA));
            }
            for (RequestBuilder request : forged) {
                anonymous.perform(request).andExpect(status().isForbidden());
            }
            assertThat(db.count("dashboards")).isEqualTo(1);
            assertThat(db.count("dashboard_revisions")).isEqualTo(1);
        }
    }

    // --- isolation -------------------------------------------------------------------------------

    @Nested
    class Isolation {

        long dashboardA;
        long dashboardB;

        @BeforeEach
        void createDashboards() throws Exception {
            dashboardA = create(as(ownerA, businessA), "A's own", layout(revenueChart));
            dashboardB = create(as(ownerB, businessB), "Secret board of B", layout(chartB));
        }

        @Test
        void anotherBusinessesDashboardIsNotFoundOnEveryEndpoint() throws Exception {
            String base = "/api/dashboards/" + dashboardB;
            for (TestUser user : List.of(ownerA, adminA, viewerA)) {
                MockMvc mvc = as(user, businessA);
                for (String path : List.of(base, base + "?revision=1", base + "/revisions")) {
                    String body = body(mvc.perform(get(path)).andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.detail").value("Dashboard not found.")).andReturn());
                    assertThat(body).doesNotContain("Secret");
                }
                assertThat(body(mvc.perform(get("/api/dashboards")).andExpect(jsonPath("$[*].name", contains("A's own"))).andReturn()))
                        .doesNotContain("Secret");
            }
            MockMvc owner = as(ownerA, businessA);
            owner.perform(putJson(dashboardB, saveBody("Hijacked", layout(revenueChart), 1)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Dashboard not found."));
            owner.perform(post(base + "/duplicate")).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Dashboard not found."));
            owner.perform(delete(base)).andExpect(status().isNotFound());
            assertThat(jdbc.queryForObject("SELECT name FROM dashboards WHERE id = ?", String.class, dashboardB))
                    .isEqualTo("Secret board of B");
            assertThat(jdbc.queryForObject("SELECT current_revision FROM dashboards WHERE id = ?", Integer.class, dashboardB))
                    .isEqualTo(1);
            assertThat(db.count("dashboards")).isEqualTo(2);
        }

        @Test
        void anotherBusinessesChartCannotBePlacedOrLookedUp() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            String body = body(owner.perform(putJson(dashboardA, saveBody("A's own", layout(revenueChart, chartB), 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[*].field", contains("layout.widgets[1].chartId")))
                    .andReturn());
            assertThat(body).doesNotContain("Secret");
            owner.perform(get("/api/charts/" + chartB + "/dashboards")).andExpect(status().isNotFound());
            assertThat(refs(dashboardA)).isEqualTo(1);
        }

        @Test
        void theDatabaseRefusesACrossBusinessReferenceThatBypassesTheValidator() {
            // Through the service's own write path, in a transaction like a save, without the validator.
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(
                    tx -> queries.replaceChartRefs(businessA, dashboardA, List.of(revenueChart, chartB))))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_dashboard_chart_refs_chart");
            // Directly: A's dashboard with B's chart, under either business id.
            assertThatThrownBy(() -> jdbc.update("INSERT INTO dashboard_chart_refs (dashboard_id, business_id, chart_id) VALUES (?, ?, ?)",
                    dashboardA, businessA, chartB))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_dashboard_chart_refs_chart");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO dashboard_chart_refs (dashboard_id, business_id, chart_id) VALUES (?, ?, ?)",
                    dashboardA, businessB, chartB))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_dashboard_chart_refs_dashboard");
            assertThat(jdbc.queryForList("SELECT chart_id FROM dashboard_chart_refs WHERE dashboard_id = ?", Long.class, dashboardA))
                    .containsExactly(revenueChart);
        }

        @Test
        void theBusinessHeaderOnlySelectsAmongTheCallersMemberships() throws Exception {
            MockMvc spoofed = as(ownerA, businessB);
            for (String path : readPaths(dashboardB, chartB)) {
                spoofed.perform(get(path))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.detail").value("Business not found."));
            }
            for (RequestBuilder write : writes(dashboardB, layout(chartB))) {
                spoofed.perform(write).andExpect(status().isNotFound());
            }

            // A member of both sees B's dashboard only while acting for B.
            TestUser both = accounts.member("both@example.com", businessA, Role.ADMIN);
            accounts.member(both, businessB, Role.ADMIN);
            as(both, businessA).perform(get("/api/dashboards/" + dashboardB)).andExpect(status().isNotFound());
            as(both, businessB).perform(get("/api/dashboards/" + dashboardB)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.widgets[0].chart.title").value("Secret chart of B"));
            as(both, businessA).perform(delete("/api/dashboards/" + dashboardB)).andExpect(status().isNotFound());
            // Acting for A, B's chart is not placeable even by a member of B.
            as(both, businessA).perform(putJson(dashboardA, saveBody("A's own", layout(chartB), 1)))
                    .andExpect(status().isBadRequest());
            as(both, businessA).perform(get("/api/dashboards")).andExpect(jsonPath("$[*].name", contains("A's own")));
            as(both, businessB).perform(get("/api/dashboards")).andExpect(jsonPath("$[*].name", contains("Secret board of B")));
            assertThat(db.count("dashboards")).isEqualTo(2);
        }
    }
}
