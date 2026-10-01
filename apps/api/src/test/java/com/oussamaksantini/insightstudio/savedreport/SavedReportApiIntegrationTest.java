package com.oussamaksantini.insightstudio.savedreport;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.BUSINESS_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
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
import com.oussamaksantini.insightstudio.report.pdf.PdfFormats;
import com.oussamaksantini.insightstudio.reporting.DateRange;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.PdfText;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.context.WebApplicationContext;

/**
 * Saved report definitions, their runs and exports (docs/saved-reports-contract.md), against
 * PostgreSQL.
 *
 * <p>Business A "Test Co" (EUR, Europe/Paris), window 2026-03-20..2026-06-10, the same sales as
 * {@code ReportApiIntegrationTest}: revenue 340.00, 5 orders, 11 units, AOV 68.00; store Alpha
 * 180.00 (3 orders, 4 units). Business B "Other Co" (USD) sells 999.00 in June and must never show up.
 */
class SavedReportApiIntegrationTest extends PostgresIntegrationTest {

    private static final String FROM = "2026-03-20";
    private static final String TO = "2026-06-10";
    private static final String WINDOW = "from=" + FROM + "&to=" + TO;

    @Autowired
    WebApplicationContext context;

    /** Anonymous requests (no session). */
    @Autowired
    MockMvc anonymous;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PeriodResolver periods;

    SqlFixture db;
    TestAccounts accounts;
    long businessA;
    long businessB;
    long storeA;
    long storeB;
    long otherStore;
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
        long p1 = db.product(businessA, "P1", "Jacket", "Outerwear", "50.00");
        long p2 = db.product(businessA, "P2", "Tee", "Tops", "20.00");
        long p3 = db.product(businessA, "P3", "Boots", "Footwear", "100.00");
        db.sale(storeA, "S0", "2026-03-19T22:59:00Z", p1, 1, "50.00");
        db.sale(storeA, "S1", "2026-04-20T10:00:00Z", p1, 1, "50.00");
        db.sale(storeA, "S2", "2026-04-30T22:30:00Z", p2, 2, "20.00");
        db.sale(storeB, "S6", "2026-05-10T10:00:00Z", p2, 5, "20.00");
        db.sale(storeB, "S3", "2026-05-31T22:30:00Z", p1, 1, "40.00", p2, 1, "20.00");
        db.sale(storeA, "S4", "2026-06-05T10:00:00Z", p3, 1, "90.00");
        db.sale(storeA, "S5", "2026-06-10T22:30:00Z", p3, 1, "100.00");

        businessB = db.business("Other Co", "other-co", "USD", "UTC");
        otherStore = db.store(businessB, "X", "Other Store", null);
        long otherProduct = db.product(businessB, "X1", "Other thing", "Misc", "999.00");
        db.sale(otherStore, "X-1", "2026-06-01T12:00:00Z", otherProduct, 1, "999.00");

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

    private static String fixed(String name, String kind, String from, String to, Long storeId) {
        return """
                {"name": %s, "kind": "%s", "range": {"type": "fixed", "from": "%s", "to": "%s"}, "storeId": %s}
                """.formatted(quote(name), kind, from, to, storeId);
    }

    private static String relative(String name, String kind, String preset, Long storeId) {
        return """
                {"name": %s, "kind": "%s", "range": {"type": "relative", "preset": "%s"}, "storeId": %s}
                """.formatted(quote(name), kind, preset, storeId);
    }

    /** A JSON string literal (control characters escaped, so they reach the API's own validation). */
    private static String quote(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                json.append('\\').append(c);
            } else if (c < 0x20) {
                json.append("\\u%04x".formatted((int) c));
            } else {
                json.append(c);
            }
        }
        return json.append('"').toString();
    }

    private static MockHttpServletRequestBuilder postJson(String body) {
        return post("/api/saved-reports").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder putJson(long id, String body) {
        return put("/api/saved-reports/" + id).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private long create(MockMvc mvc, String body) throws Exception {
        String json = mvc.perform(postJson(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static BigDecimal decimal(Object jsonNumber) {
        return new BigDecimal(String.valueOf(jsonNumber));
    }

    private static void writeSample(String name, byte[] pdf) throws Exception {
        Path dir = Path.of("target", "pdf-samples");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), pdf);
    }

    private List<String> readPaths(long id) {
        String base = "/api/saved-reports/" + id;
        return List.of("/api/saved-reports", base, base + "/report", base + "/report.csv", base + "/report.pdf");
    }

    private List<RequestBuilder> writes(long id, String body) {
        return List.of(postJson(body), putJson(id, body), delete("/api/saved-reports/" + id));
    }

    // --- definitions -----------------------------------------------------------------------------

    @Nested
    class Definitions {

        @Test
        void createsAndReadsAFixedDefinition() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            String json = owner.perform(postJson(fixed("  Q2 by month  ", "monthly", FROM, TO, storeA)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Q2 by month"))
                    .andExpect(jsonPath("$.kind").value("monthly"))
                    .andExpect(jsonPath("$.range.type").value("fixed"))
                    .andExpect(jsonPath("$.range.preset", nullValue()))
                    .andExpect(jsonPath("$.range.from").value(FROM))
                    .andExpect(jsonPath("$.range.to").value(TO))
                    .andExpect(jsonPath("$.storeId").value(storeA))
                    .andExpect(jsonPath("$.storeName").value("Alpha"))
                    .andExpect(jsonPath("$.period.from").value(FROM))
                    .andExpect(jsonPath("$.period.to").value(TO))
                    .andExpect(jsonPath("$.createdBy").value("owner-a"))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            long id = ((Number) JsonPath.read(json, "$.id")).longValue();

            String read = owner.perform(get("/api/saved-reports/" + id))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(read).isEqualTo(json);
            owner.perform(get("/api/saved-reports"))
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id").value(id));
        }

        @Test
        void relativeDefinitionsResolveTheirPeriodNowInTheBusinessTimeZone() throws Exception {
            DateRange expected = periods.resolve(SavedRange.relative(RelativePreset.PREVIOUS_QUARTER),
                    ZoneId.of("Europe/Paris"));
            as(adminA, businessA).perform(postJson(relative("Last quarter", "categories", "previous_quarter", null)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.kind").value("categories"))
                    .andExpect(jsonPath("$.range.type").value("relative"))
                    .andExpect(jsonPath("$.range.preset").value("previous_quarter"))
                    .andExpect(jsonPath("$.range.from", nullValue()))
                    .andExpect(jsonPath("$.range.to", nullValue()))
                    .andExpect(jsonPath("$.storeId", nullValue()))
                    .andExpect(jsonPath("$.storeName", nullValue()))
                    .andExpect(jsonPath("$.period.from").value(expected.from().toString()))
                    .andExpect(jsonPath("$.period.to").value(expected.to().toString()))
                    .andExpect(jsonPath("$.createdBy").value("admin-a"));
            assertThat(jdbc.queryForMap("SELECT range_type, date_from, date_to, relative_preset FROM saved_reports"))
                    .containsEntry("range_type", "relative")
                    .containsEntry("relative_preset", "previous_quarter")
                    .containsEntry("date_from", null);
        }

        @Test
        void listsByNameIgnoringCase() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            create(owner, fixed("beta", "monthly", FROM, TO, null));
            create(owner, fixed("Alpha", "categories", FROM, TO, null));
            create(owner, relative("gamma", "monthly", "last_30_days", storeB));
            as(viewerA, businessA).perform(get("/api/saved-reports"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].name", contains("Alpha", "beta", "gamma")))
                    .andExpect(jsonPath("$[2].storeName").value("Bravo"));
        }

        @Test
        void putRenamesAndEditsTheFilters() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, fixed("Old name", "monthly", FROM, TO, null));
            owner.perform(putJson(id, relative("New name", "categories", "year_to_date", storeB)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.name").value("New name"))
                    .andExpect(jsonPath("$.kind").value("categories"))
                    .andExpect(jsonPath("$.range.preset").value("year_to_date"))
                    .andExpect(jsonPath("$.range.from", nullValue()))
                    .andExpect(jsonPath("$.storeName").value("Bravo"))
                    .andExpect(jsonPath("$.createdBy").value("owner-a"));
            // Changing only the case of its own name is not a conflict.
            owner.perform(putJson(id, relative("NEW NAME", "categories", "year_to_date", null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("NEW NAME"))
                    .andExpect(jsonPath("$.storeId", nullValue()));
            assertThat(db.count("saved_reports")).isEqualTo(1);
        }

        @Test
        void deleteRemovesTheDefinition() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, fixed("Doomed", "monthly", FROM, TO, null));
            owner.perform(delete("/api/saved-reports/" + id)).andExpect(status().isNoContent());
            for (String path : readPaths(id).subList(1, 5)) {
                owner.perform(get(path))
                        .andExpect(status().isNotFound())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.detail").value("Saved report not found."));
            }
            owner.perform(delete("/api/saved-reports/" + id)).andExpect(status().isNotFound());
            owner.perform(putJson(id, fixed("Doomed", "monthly", FROM, TO, null))).andExpect(status().isNotFound());
        }

        @Test
        void namesAreUniquePerBusinessIgnoringCase() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            create(owner, fixed("Weekly look", "monthly", FROM, TO, null));
            long other = create(owner, fixed("Something else", "monthly", FROM, TO, null));
            owner.perform(postJson(fixed("WEEKLY LOOK", "categories", FROM, TO, null)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("A saved report named 'WEEKLY LOOK' already exists."));
            owner.perform(putJson(other, fixed(" weekly look ", "monthly", FROM, TO, null)))
                    .andExpect(status().isConflict());
            // Another business may use the same name.
            create(as(ownerB, businessB), fixed("Weekly look", "monthly", FROM, TO, null));
            assertThat(db.count("saved_reports")).isEqualTo(3);
        }

        @Test
        void rejectsInvalidDefinitions() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            String tooLong = "x".repeat(SavedReportService.MAX_NAME_LENGTH + 1);
            Map<String, String> invalid = Map.ofEntries(
                    Map.entry(fixed("   ", "monthly", FROM, TO, null), "Enter a name for the saved report."),
                    Map.entry(fixed(tooLong, "monthly", FROM, TO, null), "The name may be at most 120 characters."),
                    Map.entry(fixed("Bell\u0007", "monthly", FROM, TO, null), "The name contains invalid characters."),
                    Map.entry(fixed("Line\nbreak", "monthly", FROM, TO, null), "The name contains invalid characters."),
                    Map.entry(fixed("Weekly", "weekly", FROM, TO, null), "'kind' must be monthly or categories."),
                    Map.entry(fixed("Upper", "MONTHLY", FROM, TO, null), "'kind' must be monthly or categories."),
                    Map.entry("{\"name\": \"No kind\", \"range\": {\"type\": \"relative\", \"preset\": \"last_7_days\"}}",
                            "'kind' must be monthly or categories."),
                    Map.entry("{\"name\": \"No range\", \"kind\": \"monthly\"}", "'range' is required."),
                    Map.entry("{\"name\": \"Rolling\", \"kind\": \"monthly\", \"range\": {\"type\": \"rolling\"}}",
                            "'range.type' must be fixed or relative."),
                    Map.entry(relative("Unknown preset", "monthly", "last_2_days", null), "'range.preset' must be one of"),
                    Map.entry("{\"name\": \"No preset\", \"kind\": \"monthly\", \"range\": {\"type\": \"relative\"}}",
                            "'range.preset' must be one of"),
                    Map.entry("{\"name\": \"No dates\", \"kind\": \"monthly\", \"range\": {\"type\": \"fixed\", \"to\": \"2026-06-01\"}}",
                            "'range.from' is required for a fixed range."),
                    Map.entry(fixed("Bad date", "monthly", "2026-13-01", TO, null), "'range.from' must be a date"),
                    Map.entry(fixed("Backwards", "monthly", TO, FROM, null), "'from' (2026-06-10) must be on or before 'to' (2026-03-20)."),
                    Map.entry(fixed("Too long", "monthly", "2020-01-01", "2026-01-01", null),
                            "The date range may cover at most 1098 days."));
            for (Map.Entry<String, String> entry : invalid.entrySet()) {
                owner.perform(postJson(entry.getKey()))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.detail").value(containsString(entry.getValue())));
            }
            owner.perform(postJson("{not json")).andExpect(status().isBadRequest());
            owner.perform(post("/api/saved-reports").contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
            assertThat(db.count("saved_reports")).isZero();

            // The limits themselves are accepted.
            create(owner, fixed("y".repeat(SavedReportService.MAX_NAME_LENGTH), "monthly", "2023-06-10", "2026-06-10", null));
            long id = create(owner, fixed("Valid", "monthly", FROM, TO, null));
            owner.perform(putJson(id, fixed("Valid", "monthly", TO, FROM, null))).andExpect(status().isBadRequest());
        }

        @Test
        void storeMustBelongToTheBusiness() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            for (long storeId : new long[] {otherStore, 999_999}) {
                owner.perform(postJson(fixed("Elsewhere", "monthly", FROM, TO, storeId)))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.detail").value("Store %d was not found.".formatted(storeId)));
            }
            long id = create(owner, fixed("Mine", "monthly", FROM, TO, storeA));
            owner.perform(putJson(id, fixed("Mine", "monthly", FROM, TO, otherStore)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Store %d was not found.".formatted(otherStore)));
            owner.perform(get("/api/saved-reports/" + id)).andExpect(jsonPath("$.storeId").value(storeA));
        }

        @Test
        void theDatabaseRefusesAStoreOfAnotherBusiness() {
            // Defence in depth: the composite foreign key (store_id, business_id) -> stores.
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO saved_reports (business_id, name, kind, range_type, relative_preset, store_id, created_by)
                    VALUES (?, 'Sneaky', 'monthly', 'relative', 'last_7_days', ?, ?)
                    """, businessA, otherStore, ownerA.id()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // --- permissions -----------------------------------------------------------------------------

    @Nested
    class Permissions {

        @Test
        void viewersReadRunAndExportButCannotChangeAnything() throws Exception {
            long id = create(as(ownerA, businessA), fixed("Shared", "monthly", FROM, TO, null));
            MockMvc viewer = as(viewerA, businessA);
            for (String path : readPaths(id)) {
                viewer.perform(get(path)).andExpect(status().isOk());
            }
            for (RequestBuilder write : writes(id, fixed("Viewer's", "categories", FROM, TO, null))) {
                viewer.perform(write)
                        .andExpect(status().isForbidden())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.detail").value("You need the ADMIN role for this."));
            }
            assertThat(jdbc.queryForObject("SELECT name FROM saved_reports", String.class)).isEqualTo("Shared");
            assertThat(db.count("saved_reports")).isEqualTo(1);
        }

        @Test
        void ownersAndAdminsManageDefinitions() throws Exception {
            for (TestUser user : List.of(ownerA, adminA)) {
                MockMvc mvc = as(user, businessA);
                long id = create(mvc, fixed("By " + user.email(), "monthly", FROM, TO, null));
                mvc.perform(putJson(id, fixed("Renamed by " + user.email(), "monthly", FROM, TO, null)))
                        .andExpect(status().isOk());
                for (String path : readPaths(id)) {
                    mvc.perform(get(path)).andExpect(status().isOk());
                }
                mvc.perform(delete("/api/saved-reports/" + id)).andExpect(status().isNoContent());
            }
            assertThat(db.count("saved_reports")).isZero();
        }

        @Test
        void unverifiedAccountsReadButCannotWrite() throws Exception {
            long id = create(as(ownerA, businessA), fixed("Shared", "monthly", FROM, TO, null));
            MockMvc unverified = as(unverifiedAdminA, businessA);
            for (String path : readPaths(id)) {
                unverified.perform(get(path)).andExpect(status().isOk());
            }
            for (RequestBuilder write : writes(id, fixed("Unverified", "monthly", FROM, TO, null))) {
                unverified.perform(write)
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.detail").value(EmailVerificationService.VERIFY_FIRST));
            }
            assertThat(db.count("saved_reports")).isEqualTo(1);
        }

        @Test
        void anonymousCallersAreUnauthorized() throws Exception {
            long id = create(as(ownerA, businessA), fixed("Shared", "monthly", FROM, TO, null));
            for (String path : readPaths(id)) {
                anonymous.perform(get(path))
                        .andExpect(status().isUnauthorized())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
                anonymous.perform(get(path).header(BUSINESS_HEADER, businessA)).andExpect(status().isUnauthorized());
            }
            String body = fixed("Anonymous", "monthly", FROM, TO, null);
            anonymous.perform(postJson(body).with(TestAccounts.csrf())).andExpect(status().isUnauthorized());
            anonymous.perform(putJson(id, body).with(TestAccounts.csrf())).andExpect(status().isUnauthorized());
            anonymous.perform(delete("/api/saved-reports/" + id).with(TestAccounts.csrf()))
                    .andExpect(status().isUnauthorized());
            // The ad-hoc PDF needs a session too while the public demo is off.
            anonymous.perform(get("/api/reports/monthly.pdf?" + WINDOW)).andExpect(status().isUnauthorized());
            anonymous.perform(get("/api/reports/categories.pdf?" + WINDOW)).andExpect(status().isUnauthorized());
            assertThat(jdbc.queryForObject("SELECT name FROM saved_reports", String.class)).isEqualTo("Shared");
        }

        @Test
        void writesNeedTheCsrfHeader() throws Exception {
            long id = create(as(ownerA, businessA), fixed("Shared", "monthly", FROM, TO, null));
            String body = fixed("Forged", "monthly", FROM, TO, null);
            List<RequestBuilder> forged = List.of(
                    postJson(body).with(TestAccounts.as(ownerA)).header(BUSINESS_HEADER, businessA),
                    putJson(id, body).with(TestAccounts.as(ownerA)).header(BUSINESS_HEADER, businessA),
                    delete("/api/saved-reports/" + id).with(TestAccounts.as(ownerA)).header(BUSINESS_HEADER, businessA),
                    postJson(body).with(TestAccounts.as(ownerA)).with(TestAccounts.invalidCsrf())
                            .header(BUSINESS_HEADER, businessA),
                    delete("/api/saved-reports/" + id).with(TestAccounts.as(ownerA)).with(TestAccounts.invalidCsrf())
                            .header(BUSINESS_HEADER, businessA));
            for (RequestBuilder request : forged) {
                anonymous.perform(request)
                        .andExpect(status().isForbidden())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            }
            assertThat(jdbc.queryForObject("SELECT name FROM saved_reports", String.class)).isEqualTo("Shared");
        }
    }

    // --- isolation -------------------------------------------------------------------------------

    @Nested
    class Isolation {

        long definitionB;

        @BeforeEach
        void createDefinitionOfB() throws Exception {
            definitionB = create(as(ownerB, businessB), fixed("Secret plan of B", "categories", FROM, TO, otherStore));
            create(as(ownerA, businessA), fixed("A's own", "monthly", FROM, TO, null));
        }

        @Test
        void anotherBusinessesDefinitionIsNotFoundEverywhere() throws Exception {
            for (TestUser user : List.of(ownerA, adminA, viewerA)) {
                MockMvc mvc = as(user, businessA);
                for (String path : readPaths(definitionB).subList(1, 5)) {
                    String body = body(mvc.perform(get(path)).andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.detail").value("Saved report not found.")).andReturn());
                    assertThat(body).doesNotContain("Secret", "Other");
                }
            }
            MockMvc owner = as(ownerA, businessA);
            owner.perform(putJson(definitionB, fixed("Hijacked", "monthly", FROM, TO, null)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Saved report not found."));
            owner.perform(delete("/api/saved-reports/" + definitionB))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Saved report not found."));
            assertThat(jdbc.queryForObject("SELECT name FROM saved_reports WHERE id = ?", String.class, definitionB))
                    .isEqualTo("Secret plan of B");
        }

        @Test
        void theBusinessHeaderOnlySelectsAmongTheCallersMemberships() throws Exception {
            // A's owner naming business B: not a member, so the business itself is not found.
            MockMvc spoofed = as(ownerA, businessB);
            for (String path : readPaths(definitionB)) {
                spoofed.perform(get(path))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.detail").value("Business not found."));
            }
            for (RequestBuilder write : writes(definitionB, fixed("Hijacked", "monthly", FROM, TO, null))) {
                spoofed.perform(write).andExpect(status().isNotFound());
            }

            // A member of both sees B's definition only while acting for B.
            TestUser both = accounts.member("both@example.com", businessA, Role.ADMIN);
            accounts.member(both, businessB, Role.ADMIN);
            for (String path : readPaths(definitionB).subList(1, 5)) {
                as(both, businessA).perform(get(path)).andExpect(status().isNotFound());
                as(both, businessB).perform(get(path)).andExpect(status().isOk());
            }
            as(both, businessA).perform(delete("/api/saved-reports/" + definitionB)).andExpect(status().isNotFound());
            as(both, businessA).perform(get("/api/saved-reports"))
                    .andExpect(jsonPath("$[*].name", contains("A's own")));
            assertThat(db.count("saved_reports")).isEqualTo(2);
        }

        @Test
        void listsAndExportsNeverShowTheOtherBusiness() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            assertThat(body(owner.perform(get("/api/saved-reports")).andExpect(jsonPath("$", hasSize(1))).andReturn()))
                    .doesNotContain("Secret");
            long mine = create(owner, fixed("All of June", "categories", "2026-06-01", "2026-06-30", null));
            for (String path : List.of("/report", "/report.csv")) {
                String body = body(owner.perform(get("/api/saved-reports/" + mine + path)).andExpect(status().isOk()).andReturn());
                assertThat(body).doesNotContain("Misc", "999");
            }
            byte[] pdf = owner.perform(get("/api/saved-reports/" + mine + "/report.pdf")).andReturn().getResponse()
                    .getContentAsByteArray();
            assertThat(PdfText.text(pdf)).contains("Test Co").doesNotContain("Other", "Misc", "999");
            as(ownerB, businessB).perform(get("/api/saved-reports"))
                    .andExpect(jsonPath("$[*].name", contains("Secret plan of B")));
        }
    }

    // --- running and exporting -------------------------------------------------------------------

    @Nested
    class Exports {

        @Test
        void monthlyRunCsvAndPdfShowTheSameFiguresAsTheReportApi() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, fixed("Spring by month", "monthly", FROM, TO, storeA));

            String run = body(owner.perform(get("/api/saved-reports/" + id + "/report"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.savedReport.id").value(id))
                    .andExpect(jsonPath("$.savedReport.storeName").value("Alpha"))
                    .andExpect(jsonPath("$.categories").doesNotExist())
                    .andExpect(jsonPath("$.monthly.totals.revenue").value(180.00))
                    .andReturn());
            String adhoc = body(owner.perform(get("/api/reports/monthly?" + WINDOW + "&storeId=" + storeA)).andReturn());
            assertThat((Map<?, ?>) JsonPath.read(run, "$.monthly")).isEqualTo(JsonPath.read(adhoc, "$"));

            // CSV: byte for byte the ad-hoc export; its rows add up to the JSON totals.
            MvcResult csv = owner.perform(get("/api/saved-reports/" + id + "/report.csv"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("text/csv;charset=UTF-8"))
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"test-co-monthly-2026-03-20-to-2026-06-10.csv\""))
                    .andReturn();
            String csvBody = body(csv);
            assertThat(csvBody).isEqualTo(body(owner.perform(
                    get("/api/reports/monthly.csv?" + WINDOW + "&storeId=" + storeA)).andReturn()));
            BigDecimal revenue = BigDecimal.ZERO;
            long orders = 0;
            long units = 0;
            for (String line : csvBody.split("\r\n")) {
                if (line.startsWith("month")) {
                    continue;
                }
                String[] cells = line.split(",");
                revenue = revenue.add(new BigDecimal(cells[1]));
                orders += Long.parseLong(cells[2]);
                units += Long.parseLong(cells[3]);
            }
            assertThat(revenue).isEqualByComparingTo(decimal(JsonPath.read(run, "$.monthly.totals.revenue")));
            assertThat(orders).isEqualTo(((Number) JsonPath.read(run, "$.monthly.totals.orders")).longValue());
            assertThat(units).isEqualTo(((Number) JsonPath.read(run, "$.monthly.totals.unitsSold")).longValue());

            // PDF: the totals row is the JSON totals.
            MvcResult pdf = owner.perform(get("/api/saved-reports/" + id + "/report.pdf"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"test-co-monthly-2026-03-20-to-2026-06-10.pdf\""))
                    .andReturn();
            byte[] bytes = pdf.getResponse().getContentAsByteArray();
            writeSample("saved-monthly.pdf", bytes);
            String text = PdfText.text(bytes);
            String totals = "Total %s %s %s %s".formatted(
                    PdfFormats.money(decimal(JsonPath.read(run, "$.monthly.totals.revenue")), "EUR"),
                    PdfFormats.count(orders),
                    PdfFormats.count(units),
                    PdfFormats.money(decimal(JsonPath.read(run, "$.monthly.totals.averageOrderValue")), "EUR"));
            assertThat(totals).isEqualTo("Total €180.00 3 4 €60.00");
            assertThat(text)
                    .contains(totals)
                    .contains("Insight Studio", "Spring by month", "Test Co")
                    .contains("20 Mar 2026 – 10 Jun 2026 (83 days)", "Europe/Paris", "Alpha (A)", "Fixed dates", "EUR")
                    .contains("Page 1 of 1")
                    .containsPattern("Generated \\d{1,2} [A-Z][a-z]{2} \\d{4}, \\d{2}:\\d{2} \\(Europe/Paris\\)");
        }

        @Test
        void categoryRunCsvAndPdfShowTheSameFiguresAsTheReportApi() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, fixed("Categories, all stores", "categories", FROM, TO, null));

            String run = body(owner.perform(get("/api/saved-reports/" + id + "/report"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.monthly").doesNotExist())
                    .andExpect(jsonPath("$.categories.totals.revenue").value(340.00))
                    .andReturn());
            String adhoc = body(owner.perform(get("/api/reports/categories?" + WINDOW)).andReturn());
            assertThat((Map<?, ?>) JsonPath.read(run, "$.categories")).isEqualTo(JsonPath.read(adhoc, "$"));

            String csv = body(owner.perform(get("/api/saved-reports/" + id + "/report.csv"))
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"test-co-categories-2026-03-20-to-2026-06-10.csv\""))
                    .andReturn());
            assertThat(csv).isEqualTo(body(owner.perform(get("/api/reports/categories.csv?" + WINDOW)).andReturn()));
            BigDecimal revenue = BigDecimal.ZERO;
            long units = 0;
            for (String line : csv.split("\r\n")) {
                if (!line.startsWith("category")) {
                    String[] cells = line.split(",");
                    revenue = revenue.add(new BigDecimal(cells[1]));
                    units += Long.parseLong(cells[2]);
                }
            }
            assertThat(revenue).isEqualByComparingTo(decimal(JsonPath.read(run, "$.categories.totals.revenue")));
            assertThat(units).isEqualTo(((Number) JsonPath.read(run, "$.categories.totals.unitsSold")).longValue());

            byte[] pdf = owner.perform(get("/api/saved-reports/" + id + "/report.pdf"))
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"test-co-categories-2026-03-20-to-2026-06-10.pdf\""))
                    .andReturn().getResponse().getContentAsByteArray();
            String totals = "Total %s %s %s %s".formatted(
                    PdfFormats.money(decimal(JsonPath.read(run, "$.categories.totals.revenue")), "EUR"),
                    PdfFormats.count(((Number) JsonPath.read(run, "$.categories.totals.unitsSold")).longValue()),
                    PdfFormats.count(((Number) JsonPath.read(run, "$.categories.totals.orders")).longValue()),
                    PdfFormats.money(decimal(JsonPath.read(run, "$.categories.totals.averageUnitPrice")), "EUR"));
            assertThat(totals).isEqualTo("Total €340.00 11 5 €30.91");
            assertThat(PdfText.text(pdf)).contains(totals, "Categories, all stores", "All stores", "Tops €160.00 47.1% 8 3");
        }

        @Test
        void relativeDefinitionsRunForTheResolvedPeriod() throws Exception {
            MockMvc owner = as(ownerA, businessA);
            long id = create(owner, relative("Trailing year", "monthly", "last_365_days", null));
            String run = body(owner.perform(get("/api/saved-reports/" + id + "/report")).andExpect(status().isOk()).andReturn());
            String from = JsonPath.read(run, "$.savedReport.period.from");
            String to = JsonPath.read(run, "$.savedReport.period.to");
            assertThat((String) JsonPath.read(run, "$.monthly.period.from")).isEqualTo(from);
            assertThat((String) JsonPath.read(run, "$.monthly.period.to")).isEqualTo(to);
            String adhoc = body(owner.perform(get("/api/reports/monthly?from=" + from + "&to=" + to)).andReturn());
            assertThat((Map<?, ?>) JsonPath.read(run, "$.monthly")).isEqualTo(JsonPath.read(adhoc, "$"));
            byte[] pdf = owner.perform(get("/api/saved-reports/" + id + "/report.pdf")).andReturn().getResponse()
                    .getContentAsByteArray();
            assertThat(PdfText.text(pdf).replaceAll("\\s+", " ")).contains("Last 365 days (rolling: from today's date in", "(365 days)");
        }

        @Test
        void adHocPdfsFollowTheReportParameters() throws Exception {
            MockMvc viewer = as(viewerA, businessA);
            byte[] monthly = viewer.perform(get("/api/reports/monthly.pdf?" + WINDOW + "&storeId=" + storeB))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"test-co-monthly-2026-03-20-to-2026-06-10.pdf\""))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(PdfText.text(monthly)).contains("Monthly report", "Test Co", "Bravo (B)", "Total €160.00 2 7 €80.00")
                    .doesNotContain("Range");
            byte[] categories = viewer.perform(get("/api/reports/categories.pdf?" + WINDOW))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"test-co-categories-2026-03-20-to-2026-06-10.pdf\""))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(PdfText.text(categories)).contains("Category report", "All stores", "Total €340.00 11 5 €30.91");

            viewer.perform(get("/api/reports/monthly.pdf?" + WINDOW + "&storeId=" + otherStore))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            viewer.perform(get("/api/reports/monthly.pdf?from=2026-06-10&to=2026-03-20"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            // The CSV filename keeps its original form.
            viewer.perform(get("/api/reports/monthly.csv?" + WINDOW))
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"monthly-2026-03-20-to-2026-06-10.csv\""));
        }

        @Test
        void aLongCategoryReportSpansSeveralPagesWithItsHeaderOnEach() throws Exception {
            long business = db.business("Many Categories Ltd", "many-categories", "GBP", "Europe/London");
            long store = db.store(business, "M", "Main", "London");
            TestUser owner = accounts.member("owner-many@example.com", business, Role.OWNER);
            for (int i = 1; i <= 120; i++) {
                long product = db.product(business, "SKU-" + i, "Product " + i, "Category %03d".formatted(i), "10.00");
                db.sale(store, "R-" + i, "2026-05-%02dT12:00:00Z".formatted(1 + i % 28), product, 1, "%d.00".formatted(i));
            }
            MockMvc mvc = as(owner, business);
            long id = create(mvc, fixed("Every category", "categories", "2026-05-01", "2026-05-31", null));
            String run = body(mvc.perform(get("/api/saved-reports/" + id + "/report"))
                    .andExpect(jsonPath("$.categories.rows", hasSize(120)))
                    .andExpect(jsonPath("$.categories.totals.revenue").value(7260.00))
                    .andReturn());
            byte[] pdf = mvc.perform(get("/api/saved-reports/" + id + "/report.pdf")).andReturn().getResponse()
                    .getContentAsByteArray();
            writeSample("saved-categories-long.pdf", pdf);

            List<String> pages = PdfText.pages(pdf);
            assertThat(pages).hasSizeGreaterThanOrEqualTo(3);
            for (int i = 0; i < pages.size(); i++) {
                assertThat(pages.get(i)).as("page %d", i + 1)
                        .contains("Category Revenue Share Units Orders Avg unit price")
                        .contains("Page %d of %d".formatted(i + 1, pages.size()))
                        .contains("(Europe/London)");
            }
            String all = String.join("\n", pages);
            for (int i = 1; i <= 120; i++) {
                assertThat(all).contains("Category %03d".formatted(i));
            }
            assertThat(pages.getLast()).contains("Total £7,260.00 120 120 £60.50");
            assertThat(decimal(JsonPath.read(run, "$.categories.totals.averageUnitPrice"))).isEqualByComparingTo("60.50");
        }

        @Test
        void aBusinessWithoutSalesGetsAnExplicitEmptyPdf() throws Exception {
            long business = db.business("Empty Shop", "empty-shop", "USD", "America/New_York");
            TestUser owner = accounts.member("owner-empty@example.com", business, Role.OWNER);
            MockMvc mvc = as(owner, business);
            for (String kind : List.of("monthly", "categories")) {
                long id = create(mvc, relative("Nothing " + kind, kind, "previous_month", null));
                mvc.perform(get("/api/saved-reports/" + id + "/report"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$." + kind + ".totals.revenue").value(0))
                        .andExpect(jsonPath("$." + kind + ".totals.orders").value(0));
                byte[] pdf = mvc.perform(get("/api/saved-reports/" + id + "/report.pdf"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsByteArray();
                if (kind.equals("monthly")) {
                    writeSample("saved-monthly-empty.pdf", pdf);
                }
                assertThat(PdfText.text(pdf).replaceAll("\\s+", " ")).contains("No sales in this period", "Empty Shop", "$0.00", "Page 1 of 1",
                        "Previous month (rolling: from today's date in America/New_York)", "America/New_York");
            }
        }
    }
}
