package com.oussamaksantini.insightstudio.chart;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.BUSINESS_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

/**
 * Saved charts, their revisions and the permission and isolation rules (docs/chart-builder-contract.md
 * §4), against PostgreSQL. The figures themselves are checked in {@code ChartMetricsIntegrationTest}.
 *
 * <p>Business A "Test Co" (EUR, Europe/Paris) with stores Alpha and Bravo; business B "Other Co" whose
 * store, product, category and charts must never be usable or visible from A. This context has no Cube.
 */
class ChartApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext context;

    /** Anonymous requests (no session). */
    @Autowired
    MockMvc anonymous;

    @Autowired
    JdbcTemplate jdbc;

    SqlFixture db;
    TestAccounts accounts;
    long businessA;
    long businessB;
    long storeA;
    long storeB;
    long productA;
    long otherStore;
    long otherProduct;
    TestUser ownerA;
    TestUser adminA;
    TestUser viewerA;
    TestUser unverifiedAdminA;
    TestUser ownerB;

    @BeforeEach
    void loadFixture() {
        db = new SqlFixture(jdbc);
        db.clear();
        accounts = new TestAccounts(jdbc);

        businessA = db.business("Test Co", "test-co", "EUR", "Europe/Paris");
        storeA = db.store(businessA, "A", "Alpha", "Paris");
        storeB = db.store(businessA, "B", "Bravo", "Lyon");
        productA = db.product(businessA, "P1", "Jacket", "Outerwear", "50.00");
        long tee = db.product(businessA, "P2", "Tee", "Tops", "20.00");
        db.sale(storeA, "S1", "2026-04-20T10:00:00Z", productA, 1, "50.00");
        db.sale(storeB, "S2", "2026-05-10T10:00:00Z", tee, 5, "20.00", productA, 1, "45.00");

        businessB = db.business("Other Co", "other-co", "USD", "UTC");
        otherStore = db.store(businessB, "X", "Other Store", null);
        otherProduct = db.product(businessB, "X1", "Other thing", "Secret category", "999.00");
        db.sale(otherStore, "X-1", "2026-05-01T12:00:00Z", otherProduct, 1, "999.00");

        ownerA = accounts.member("owner-a@example.com", businessA, Role.OWNER);
        adminA = accounts.member("admin-a@example.com", businessA, Role.ADMIN);
        viewerA = accounts.member("viewer-a@example.com", businessA, Role.VIEWER);
        unverifiedAdminA = accounts.unverifiedUser("unverified-a@example.com");
        accounts.member(unverifiedAdminA, businessA, Role.ADMIN);
        ownerB = accounts.member("owner-b@example.com", businessB, Role.OWNER);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private MockMvc as(TestUser user, long businessId) {
        return TestAccounts.mvc(context, user, businessId);
    }

    /** A valid bar chart of revenue by store over April–May 2026. */
    static String chart(String title) {
        return chart(title, "{\"storeIds\": [], \"categories\": [], \"productIds\": []}");
    }

    static String chart(String title, String filters) {
        return """
                {"schemaVersion": 1, "title": "%s", "visualization": "bar", "metrics": ["revenue"], "groupBy": "store",
                 "granularity": null, "range": {"type": "fixed", "from": "2026-04-01", "to": "2026-05-31"},
                 "filters": %s, "limit": 10, "engine": "sql"}
                """.formatted(title, filters);
    }

    private static MockHttpServletRequestBuilder postJson(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder putJson(long id, String body) {
        return put("/api/charts/" + id).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String update(String definition, int expectedRevision) {
        return "{\"definition\": %s, \"expectedRevision\": %d}".formatted(definition, expectedRevision);
    }

    private long create(MockMvc mvc, String body) throws Exception {
        String json = body(mvc.perform(postJson("/api/charts", body)).andExpect(status().isCreated()).andReturn());
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> readPaths(long id) {
        String base = "/api/charts/" + id;
        return List.of("/api/charts", "/api/charts/catalog", base, base + "/revisions", base + "/revisions/1",
                base + "/data", base + "/data?revision=1");
    }

    /** Every endpoint that names a chart id. */
    private static List<String> idPaths(long id) {
        String base = "/api/charts/" + id;
        return List.of(base, base + "/revisions", base + "/revisions/1", base + "/data", base + "/data?revision=1");
    }

    private static List<RequestBuilder> writes(long id, String definition) {
        return List.of(
                postJson("/api/charts", definition),
                postJson("/api/charts/preview", definition),
                putJson(id, update(definition, 1)),
                postJson("/api/charts/" + id + "/duplicate", "{}"),
                delete("/api/charts/" + id));
    }

    // --- definitions and revisions ---------------------------------------------------------------

    @Nested
    class Definitions {

        @Test
        void createsReadsListsAndRunsAChart() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            String created = body(owner.perform(postJson("/api/charts", chart("  Revenue by store  ")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.title").value("Revenue by store"))
                    .andExpect(jsonPath("$.revision").value(1))
                    .andExpect(jsonPath("$.definition.title").value("Revenue by store"))
                    .andExpect(jsonPath("$.definition.limit").value(10))
                    .andExpect(jsonPath("$.definition.engine").value("sql"))
                    .andExpect(jsonPath("$.createdBy").value("owner-a"))
                    .andExpect(jsonPath("$.updatedBy").value("owner-a"))
                    .andExpect(jsonPath("$.createdAt").isString())
                    .andReturn());
            long id = ((Number) JsonPath.read(created, "$.id")).longValue();

            owner.perform(get("/api/charts/" + id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.definition.groupBy").value("store"));
            owner.perform(get("/api/charts"))
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id").value(id))
                    .andExpect(jsonPath("$[0].title").value("Revenue by store"))
                    .andExpect(jsonPath("$[0].visualization").value("bar"))
                    .andExpect(jsonPath("$[0].metrics", contains("revenue")))
                    .andExpect(jsonPath("$[0].groupBy").value("store"))
                    .andExpect(jsonPath("$[0].revision").value(1))
                    .andExpect(jsonPath("$[0].updatedBy").value("owner-a"))
                    .andExpect(jsonPath("$[0].updatedAt").isString());
            owner.perform(get("/api/charts/" + id + "/data"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Report-Engine", "sql"))
                    .andExpect(jsonPath("$.period.from").value("2026-04-01"))
                    .andExpect(jsonPath("$.timeZone").value("Europe/Paris"))
                    .andExpect(jsonPath("$.currency").value("EUR"))
                    .andExpect(jsonPath("$.engine").value("sql"))
                    .andExpect(jsonPath("$.columns[0].key").value("group"))
                    .andExpect(jsonPath("$.columns[0].label").value("Store"))
                    .andExpect(jsonPath("$.columns[1].unit").value("money"))
                    .andExpect(jsonPath("$.rows[0].label").value("Bravo"))
                    .andExpect(jsonPath("$.rows[0].values.revenue").value(145.00))
                    .andExpect(jsonPath("$.rows[1].label").value("Alpha"))
                    .andExpect(jsonPath("$.totals.revenue").value(195.00))
                    .andExpect(jsonPath("$.truncated").value(false))
                    .andExpect(jsonPath("$.totalGroups").value(2))
                    .andExpect(jsonPath("$.generatedAt").isString());
            // The definition is stored normalized, as the API returns it.
            assertThat(jdbc.queryForObject("SELECT definition->>'title' FROM chart_definition_revisions", String.class))
                    .isEqualTo("Revenue by store");
        }

        @Test
        void listsByTitleIgnoringCase() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            for (String title : List.of("beta", "Alpha", "gamma")) {
                create(owner, chart(title));
            }
            owner.perform(get("/api/charts")).andExpect(jsonPath("$[*].title", contains("Alpha", "beta", "gamma")));
        }

        @Test
        void titlesAreUniquePerBusinessIgnoringCase() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long first = create(owner, chart("Weekly sales"));
            owner.perform(postJson("/api/charts", chart("WEEKLY SALES")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("A chart titled 'WEEKLY SALES' already exists."));
            long second = create(owner, chart("Other"));
            owner.perform(putJson(second, update(chart("weekly sales"), 1))).andExpect(status().isConflict());
            // Renaming a chart to its own title in another case is fine.
            owner.perform(putJson(first, update(chart("Weekly Sales"), 1))).andExpect(status().isOk());
            // Another business may use the same title.
            create(as(ownerB, businessB), chart("Weekly sales", "{}"));
        }

        @Test
        void putSavesANewRevisionAndOldRevisionsStayReadableAndRunnable() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, chart("By store"));
            String byCategory = chart("By category").replace("\"groupBy\": \"store\"", "\"groupBy\": \"category\"");

            as(adminA, businessA).perform(putJson(id, update(byCategory, 1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.revision").value(2))
                    .andExpect(jsonPath("$.title").value("By category"))
                    .andExpect(jsonPath("$.definition.groupBy").value("category"))
                    .andExpect(jsonPath("$.createdBy").value("owner-a"))
                    .andExpect(jsonPath("$.updatedBy").value("admin-a"));

            owner.perform(get("/api/charts/" + id + "/revisions"))
                    .andExpect(jsonPath("$[*].revision", contains(2, 1)))
                    .andExpect(jsonPath("$[*].createdBy", contains("admin-a", "owner-a")))
                    .andExpect(jsonPath("$[0].definition").doesNotExist());
            owner.perform(get("/api/charts/" + id + "/revisions/1"))
                    .andExpect(jsonPath("$.revision").value(1))
                    .andExpect(jsonPath("$.definition.groupBy").value("store"))
                    .andExpect(jsonPath("$.definition.title").value("By store"))
                    .andExpect(jsonPath("$.createdBy").value("owner-a"));
            owner.perform(get("/api/charts/" + id + "/data?revision=1"))
                    .andExpect(jsonPath("$.groupBy").value("store"))
                    .andExpect(jsonPath("$.rows[0].label").value("Bravo"));
            owner.perform(get("/api/charts/" + id + "/data"))
                    .andExpect(jsonPath("$.groupBy").value("category"))
                    .andExpect(jsonPath("$.rows[*].label", contains("Tops", "Outerwear")));
            owner.perform(get("/api/charts/" + id + "/data?revision=2")).andExpect(jsonPath("$.groupBy").value("category"));
            owner.perform(get("/api/charts/" + id + "/data?revision=3"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Revision 3 of this chart was not found."));
            owner.perform(get("/api/charts/" + id + "/revisions/3")).andExpect(status().isNotFound());
        }

        @Test
        void aStaleExpectedRevisionIsAConflictAndChangesNothing() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, chart("Shared"));
            owner.perform(putJson(id, update(chart("First edit"), 1))).andExpect(status().isOk());
            // A second editor who also opened revision 1.
            as(adminA, businessA).perform(putJson(id, update(chart("Second edit"), 1)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value(ChartService.STALE));
            owner.perform(get("/api/charts/" + id))
                    .andExpect(jsonPath("$.title").value("First edit"))
                    .andExpect(jsonPath("$.revision").value(2));
            assertThat(db.count("chart_definition_revisions")).isEqualTo(2);

            owner.perform(putJson(id, "{\"definition\": " + chart("No revision") + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("expectedRevision"));
            owner.perform(putJson(id, update(chart("x").replace("\"bar\"", "\"radar\""), 2)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("visualization"));
        }

        @Test
        void duplicateCreatesRevisionOneWithAUniqueTitle() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, chart("Sales"));
            owner.perform(putJson(id, update(chart("Sales"), 1))).andExpect(status().isOk());

            String copy = body(owner.perform(postJson("/api/charts/" + id + "/duplicate", "{}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.title").value("Copy of Sales"))
                    .andExpect(jsonPath("$.revision").value(1))
                    .andExpect(jsonPath("$.definition.title").value("Copy of Sales"))
                    .andExpect(jsonPath("$.definition.groupBy").value("store"))
                    .andReturn());
            long copyId = ((Number) JsonPath.read(copy, "$.id")).longValue();
            assertThat(copyId).isNotEqualTo(id);
            owner.perform(get("/api/charts/" + copyId + "/revisions")).andExpect(jsonPath("$[*].revision", contains(1)));

            // Without a body too; the next copies are numbered.
            owner.perform(post("/api/charts/" + id + "/duplicate"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.title").value("Copy of Sales (2)"));
            owner.perform(postJson("/api/charts/" + id + "/duplicate", "{\"title\": \"My own copy\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.title").value("My own copy"));
            owner.perform(postJson("/api/charts/" + id + "/duplicate", "{\"title\": \"my own COPY\"}"))
                    .andExpect(status().isConflict());
            owner.perform(postJson("/api/charts/" + id + "/duplicate", "{\"title\": \"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("title"));

            // A long title is shortened to fit "Copy of ".
            long longOne = create(owner, chart("L".repeat(120)));
            owner.perform(post("/api/charts/" + longOne + "/duplicate"))
                    .andExpect(jsonPath("$.title").value("Copy of " + "L".repeat(112)));
        }

        @Test
        void deleteRemovesTheChartAndItsRevisions() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, chart("Short-lived"));
            owner.perform(putJson(id, update(chart("Short-lived"), 1))).andExpect(status().isOk());
            long kept = create(owner, chart("Kept"));
            assertThat(db.count("chart_definition_revisions")).isEqualTo(3);

            owner.perform(delete("/api/charts/" + id)).andExpect(status().isNoContent());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chart_definition_revisions WHERE chart_id = ?",
                    Long.class, id)).isZero();
            assertThat(db.count("chart_definition_revisions")).isEqualTo(1);
            for (String path : idPaths(id)) {
                owner.perform(get(path)).andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.detail").value("Chart not found."));
            }
            owner.perform(delete("/api/charts/" + id)).andExpect(status().isNotFound());
            owner.perform(get("/api/charts/" + kept)).andExpect(status().isOk());
        }

        @Test
        void aBusinessHasAtMost200Charts() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, chart("Chart 1"));
            jdbc.update("""
                    INSERT INTO chart_definitions (business_id, title, created_by, updated_by)
                    SELECT ?, 'Filler ' || n, ?, ? FROM generate_series(2, 200) AS n
                    """, businessA, ownerA.id(), ownerA.id());
            owner.perform(postJson("/api/charts", chart("Chart 201")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("A business can have at most 200 charts. Delete one before adding another."));
            owner.perform(post("/api/charts/" + id + "/duplicate")).andExpect(status().isConflict());
            // Saving a revision of an existing chart is not a new chart.
            owner.perform(putJson(id, update(chart("Chart 1"), 1))).andExpect(status().isOk());
            // Another business is not affected.
            create(as(ownerB, businessB), chart("B's chart", "{}"));
            owner.perform(delete("/api/charts/" + id)).andExpect(status().isNoContent());
            create(owner, chart("Chart 201"));
        }
    }

    // --- validation over HTTP --------------------------------------------------------------------

    @Nested
    class Validation {

        @Test
        void invalidDefinitionsAreFieldLevelProblemDetails() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            String invalid = chart("").replace("\"bar\"", "\"pie\"").replace("[\"revenue\"]", "[\"average_order_value\"]")
                    .replace("\"limit\": 10", "\"limit\": 0");
            owner.perform(postJson("/api/charts", invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.errors[*].field", contains("title", "limit", "metrics[0]")))
                    .andExpect(jsonPath("$.errors[2].message").value(ChartRules.PIE_AVERAGE))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Enter a title for the chart.")));
            for (String path : List.of("/api/charts", "/api/charts/preview")) {
                owner.perform(postJson(path, "[]")).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errors[0].field").value("definition"));
                owner.perform(post(path)).andExpect(status().isBadRequest());
                owner.perform(postJson(path, "{not json")).andExpect(status().isBadRequest());
            }
            assertThat(db.count("chart_definitions")).isZero();
        }

        @Test
        void filterIdsOfAnotherBusinessAreRefusedWithoutConfirmingThem() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            String filters = "{\"storeIds\": [%d, %d], \"categories\": [\"Secret category\"], \"productIds\": [%d, 987654]}"
                    .formatted(storeA, otherStore, otherProduct);
            for (String path : List.of("/api/charts", "/api/charts/preview")) {
                String body = body(owner.perform(postJson(path, chart("Peeking", filters)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errors[*].field", contains(
                                "filters.storeIds[1]", "filters.categories[0]", "filters.productIds[0]", "filters.productIds[1]")))
                        .andExpect(jsonPath("$.errors[0].message")
                                .value("Store %d is not a store of this business.".formatted(otherStore)))
                        .andExpect(jsonPath("$.errors[2].message")
                                .value("Product %d is not a product of this business.".formatted(otherProduct)))
                        .andExpect(jsonPath("$.errors[3].message").value("Product 987654 is not a product of this business."))
                        .andReturn());
                assertThat(body).doesNotContain("Other Store", "Other thing", "999");
            }
            assertThat(db.count("chart_definitions")).isZero();
        }

        @Test
        void cubeChartsNeedACubeAndTheCatalogueOffersOnlySql() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            owner.perform(get("/api/charts/catalog"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.engines", contains("sql")))
                    .andExpect(jsonPath("$.defaultEngine").value("sql"))
                    .andExpect(jsonPath("$.filters[0].options[*].label", contains("Alpha", "Bravo")))
                    .andExpect(jsonPath("$.filters[1].options[*].value", contains("Outerwear", "Tops")))
                    .andExpect(jsonPath("$.filters[2].search").value("/api/products?q="))
                    .andExpect(jsonPath("$.presets[0].key").value("last_7_days"))
                    .andExpect(jsonPath("$.dimensions[1].granularities", contains("day", "week", "month")))
                    .andExpect(jsonPath("$.dimensions[0].granularities").doesNotExist());
            String cube = chart("On Cube").replace("\"engine\": \"sql\"", "\"engine\": \"cube\"");
            for (String path : List.of("/api/charts", "/api/charts/preview")) {
                owner.perform(postJson(path, cube))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errors[0].field").value("engine"))
                        .andExpect(jsonPath("$.errors[0].message").value("Cube is not configured on this server; use the sql engine."));
            }
        }

        @Test
        void aStoredDefinitionThatNoLongerValidatesIsReturnedButAnswers400WhenRun() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long store = db.store(businessA, "C", "Closing soon", "Nice");
            long id = create(owner, chart("Closing store", "{\"storeIds\": [%d]}".formatted(store)));
            jdbc.update("DELETE FROM stores WHERE id = ?", store);

            owner.perform(get("/api/charts/" + id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.definition.filters.storeIds[0]").value(store));
            owner.perform(get("/api/charts/" + id + "/data"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("filters.storeIds[0]"))
                    .andExpect(jsonPath("$.errors[0].message").value("Store %d is not a store of this business.".formatted(store)));
        }

        @Test
        void unknownIdsAreNotFoundAndMalformedIdsAreBadRequests() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            for (String path : idPaths(424242)) {
                owner.perform(get(path)).andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.detail").value("Chart not found."));
            }
            owner.perform(get("/api/charts/abc")).andExpect(status().isBadRequest());
            owner.perform(get("/api/charts/1/data?revision=x")).andExpect(status().isBadRequest());
        }
    }

    // --- permissions -----------------------------------------------------------------------------

    @Nested
    class Permissions {

        @Test
        void viewersReadAndRunButCannotPreviewOrChangeAnything() throws Exception {
            long id = create(as(ownerA, businessA), chart("Shared"));
            MockMvc viewer = as(viewerA, businessA);
            for (String path : readPaths(id)) {
                viewer.perform(get(path)).andExpect(status().isOk());
            }
            for (RequestBuilder write : writes(id, chart("Viewer's"))) {
                viewer.perform(write)
                        .andExpect(status().isForbidden())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.detail").value("You need the ADMIN role for this."));
            }
            assertThat(jdbc.queryForObject("SELECT title FROM chart_definitions", String.class)).isEqualTo("Shared");
            assertThat(db.count("chart_definition_revisions")).isEqualTo(1);
        }

        @Test
        void ownersAndAdminsPreviewAndManageCharts() throws Exception {
            for (TestUser user : List.of(ownerA, adminA)) {
                MockMvc mvc = as(user, businessA);
                mvc.perform(postJson("/api/charts/preview", chart("Preview")))
                        .andExpect(status().isOk())
                        .andExpect(header().string("X-Report-Engine", "sql"));
                long id = create(mvc, chart("By " + user.email()));
                mvc.perform(putJson(id, update(chart("Renamed by " + user.email()), 1))).andExpect(status().isOk());
                for (String path : readPaths(id)) {
                    mvc.perform(get(path)).andExpect(status().isOk());
                }
                String copy = body(mvc.perform(post("/api/charts/" + id + "/duplicate")).andExpect(status().isCreated()).andReturn());
                mvc.perform(delete("/api/charts/" + JsonPath.read(copy, "$.id"))).andExpect(status().isNoContent());
                mvc.perform(delete("/api/charts/" + id)).andExpect(status().isNoContent());
            }
            assertThat(db.count("chart_definitions")).isZero();
        }

        @Test
        void unverifiedAccountsReadButCannotPreviewOrWrite() throws Exception {
            long id = create(as(ownerA, businessA), chart("Shared"));
            MockMvc unverified = as(unverifiedAdminA, businessA);
            for (String path : readPaths(id)) {
                unverified.perform(get(path)).andExpect(status().isOk());
            }
            for (RequestBuilder write : writes(id, chart("Unverified"))) {
                unverified.perform(write)
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.detail").value(EmailVerificationService.VERIFY_FIRST));
            }
            assertThat(db.count("chart_definitions")).isEqualTo(1);
        }

        @Test
        void anonymousCallersAreUnauthorized() throws Exception {
            long id = create(as(ownerA, businessA), chart("Shared"));
            for (String path : readPaths(id)) {
                anonymous.perform(get(path))
                        .andExpect(status().isUnauthorized())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
                anonymous.perform(get(path).header(BUSINESS_HEADER, businessA)).andExpect(status().isUnauthorized());
            }
            for (RequestBuilder write : writes(id, chart("Anonymous"))) {
                anonymous.perform(((MockHttpServletRequestBuilder) write).with(TestAccounts.csrf()))
                        .andExpect(status().isUnauthorized());
            }
            assertThat(jdbc.queryForObject("SELECT title FROM chart_definitions", String.class)).isEqualTo("Shared");
        }

        @Test
        void writesNeedTheCsrfHeader() throws Exception {
            long id = create(as(ownerA, businessA), chart("Shared"));
            List<RequestBuilder> forged = new ArrayList<>();
            for (RequestBuilder write : writes(id, chart("Forged"))) {
                forged.add(((MockHttpServletRequestBuilder) write).with(TestAccounts.as(ownerA)).header(BUSINESS_HEADER, businessA));
            }
            for (RequestBuilder write : writes(id, chart("Forged"))) {
                forged.add(((MockHttpServletRequestBuilder) write).with(TestAccounts.as(ownerA)).with(TestAccounts.invalidCsrf())
                        .header(BUSINESS_HEADER, businessA));
            }
            for (RequestBuilder request : forged) {
                anonymous.perform(request)
                        .andExpect(status().isForbidden())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            }
            assertThat(jdbc.queryForObject("SELECT title FROM chart_definitions", String.class)).isEqualTo("Shared");
            assertThat(db.count("chart_definitions")).isEqualTo(1);
        }
    }

    // --- isolation -------------------------------------------------------------------------------

    @Nested
    class Isolation {

        long chartB;

        @BeforeEach
        void createChartOfB() throws Exception {
            chartB = create(as(ownerB, businessB), chart("Secret plan of B", "{\"storeIds\": [%d]}".formatted(otherStore)));
            create(as(ownerA, businessA), chart("A's own"));
        }

        @Test
        void anotherBusinessesChartIsNotFoundOnEveryEndpoint() throws Exception {
            for (TestUser user : List.of(ownerA, adminA, viewerA)) {
                MockMvc mvc = as(user, businessA);
                for (String path : idPaths(chartB)) {
                    String body = body(mvc.perform(get(path)).andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.detail").value("Chart not found.")).andReturn());
                    assertThat(body).doesNotContain("Secret", "Other");
                }
            }
            MockMvc owner = as(ownerA, businessA);
            owner.perform(putJson(chartB, update(chart("Hijacked"), 1)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Chart not found."));
            owner.perform(postJson("/api/charts/" + chartB + "/duplicate", "{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Chart not found."));
            owner.perform(delete("/api/charts/" + chartB))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Chart not found."));
            assertThat(jdbc.queryForObject("SELECT title FROM chart_definitions WHERE id = ?", String.class, chartB))
                    .isEqualTo("Secret plan of B");
            assertThat(jdbc.queryForObject("SELECT current_revision FROM chart_definitions WHERE id = ?", Integer.class, chartB))
                    .isEqualTo(1);
        }

        @Test
        void theBusinessHeaderOnlySelectsAmongTheCallersMemberships() throws Exception {
            // A's owner naming business B: not a member, so the business itself is not found.
            MockMvc spoofed = as(ownerA, businessB);
            for (String path : readPaths(chartB)) {
                spoofed.perform(get(path))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.detail").value("Business not found."));
            }
            for (RequestBuilder write : writes(chartB, chart("Hijacked"))) {
                spoofed.perform(write).andExpect(status().isNotFound());
            }

            // A member of both sees B's chart only while acting for B.
            TestUser both = accounts.member("both@example.com", businessA, Role.ADMIN);
            accounts.member(both, businessB, Role.ADMIN);
            for (String path : idPaths(chartB)) {
                as(both, businessA).perform(get(path)).andExpect(status().isNotFound());
                as(both, businessB).perform(get(path)).andExpect(status().isOk());
            }
            as(both, businessA).perform(delete("/api/charts/" + chartB)).andExpect(status().isNotFound());
            as(both, businessA).perform(get("/api/charts")).andExpect(jsonPath("$[*].title", contains("A's own")));
            as(both, businessB).perform(get("/api/charts")).andExpect(jsonPath("$[*].title", contains("Secret plan of B")));
            assertThat(db.count("chart_definitions")).isEqualTo(2);
        }

        @Test
        void listsCataloguesAndResultsNeverShowTheOtherBusiness() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            assertThat(body(owner.perform(get("/api/charts")).andExpect(jsonPath("$", hasSize(1))).andReturn()))
                    .doesNotContain("Secret");
            assertThat(body(owner.perform(get("/api/charts/catalog")).andReturn()))
                    .doesNotContain("Other Store", "Secret category");
            String preview = body(owner.perform(postJson("/api/charts/preview",
                    chart("All").replace("\"store\"", "\"category\""))).andExpect(status().isOk()).andReturn());
            assertThat(preview).doesNotContain("Secret", "999");
            assertThat((Double) JsonPath.read(preview, "$.totals.revenue")).isEqualTo(195.0);
        }
    }
}
