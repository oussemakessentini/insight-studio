package com.oussamaksantini.insightstudio.accountdata;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Business ZIP and account JSON exports (docs/account-management-contract.md §3): their files and
 * contents match the database, another business's data never appears, no secret is exported, formula
 * injection is guarded, and both are rate limited.
 */
class ExportIntegrationTest extends PostgresIntegrationTest {

    private static final List<String> ENTRIES = List.of("business.json", "members.csv", "invitations.csv", "stores.csv",
            "products.csv", "sales.csv", "sale_items.csv", "imports.csv", "saved_reports.csv", "charts.json",
            "dashboards.json", "audit_events.csv");
    private static final String INVITATION_SHA = "a".repeat(64);
    private static final String OTHER_INVITATION_SHA = "b".repeat(64);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    SqlFixture db;
    TestAccounts accounts;
    long business;
    long other;
    TestUser owner;
    TestUser admin;
    TestUser otherOwner;

    @BeforeEach
    void setUp() throws Exception {
        db = new SqlFixture(jdbc);
        db.clear();
        accounts = new TestAccounts(jdbc);
        business = db.business("Export Co", "export-co", "EUR", "Europe/Paris");
        other = db.business("Secret Other Co", "secret-other", "EUR", "UTC");
        owner = accounts.member("owner@export.co", business, Role.OWNER);
        admin = accounts.member("admin@export.co", business, Role.ADMIN);
        otherOwner = accounts.member("owner@secret-other.co", other, Role.OWNER);
        fill(business, owner, "EXP", "=HYPERLINK(\"http://evil\")", INVITATION_SHA);
        fill(other, otherOwner, "SECRET-OTHER", "Other product", OTHER_INVITATION_SHA);
        // Audit events of both businesses (chart.created through the API).
        createChart(business, owner, "Export revenue");
        createChart(other, otherOwner, "Secret other revenue");
    }

    private void fill(long businessId, TestUser user, String prefix, String productName, String invitationSha) {
        long store = db.store(businessId, prefix + "-S1", prefix + " store, \"main\"", "Lyon");
        db.store(businessId, prefix + "-S2", prefix + " second", null);
        long p1 = db.product(businessId, prefix + "-P1", productName, "Misc", "12.50");
        long p2 = db.product(businessId, prefix + "-P2", prefix + " other", "Misc", "3.00");
        long s1 = db.sale(store, prefix + "-R1", "2026-03-01T10:00:00Z", p1, 2, "12.50", p2, 1, "3.00");
        long s2 = db.sale(store, prefix + "-R2", "2026-03-02T10:00:00Z", p2, 4, "2.75");
        db.importBatch(businessId, prefix + "-file.csv", "39.25", s1, s2);
        jdbc.update("""
                INSERT INTO invitations (business_id, email, role, token_sha256, invited_by, expires_at)
                VALUES (?, ?, 'VIEWER', ?, ?, now() + interval '7 days')
                """, businessId, prefix.toLowerCase() + "-invitee@example.com", invitationSha, user.id());
        jdbc.update("""
                INSERT INTO saved_reports (business_id, name, kind, range_type, relative_preset, created_by)
                VALUES (?, ?, 'monthly', 'relative', 'last_30_days', ?)
                """, businessId, prefix + " report", user.id());
    }

    private void createChart(long businessId, TestUser user, String title) throws Exception {
        String chart = """
                {"schemaVersion": 1, "title": "%s", "visualization": "bar", "metrics": ["revenue"], "groupBy": "store",
                 "granularity": null, "range": {"type": "fixed", "from": "2026-03-01", "to": "2026-03-31"},
                 "filters": {"storeIds": [], "categories": [], "productIds": []}, "limit": 10, "engine": "sql"}
                """.formatted(title);
        String created = mvc.perform(post("/api/charts").with(as(user, businessId)).contentType(MediaType.APPLICATION_JSON)
                .content(chart)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long chartId = ((Number) JsonPath.read(created, "$.id")).longValue();
        String layout = """
                {"name": "%s board", "layout": {"schemaVersion": 1, "widgets": [{"id": "only", "chartId": %d}],
                 "desktop": {"columns": 12, "items": [{"id": "only", "x": 0, "y": 0, "w": 12, "h": 6}]},
                 "mobile": {"columns": 4, "items": [{"id": "only", "x": 0, "y": 0, "w": 4, "h": 6}]}}}
                """.formatted(title, chartId);
        mvc.perform(post("/api/dashboards").with(as(user, businessId)).contentType(MediaType.APPLICATION_JSON).content(layout))
                .andExpect(status().isCreated());
    }

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static List<String> lines(String csv) {
        assertThat(csv).endsWith("\r\n");
        return List.of(csv.split("\r\n"));
    }

    private long count(String table) {
        String column = table.equals("businesses") ? "id" : "business_id";
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Long.class, business);
    }

    @Test
    void theBusinessZipHoldsEveryFileWithTheDatabaseRowsAndNoSecrets() throws Exception {
        MvcResult result = mvc.perform(get("/api/businesses/" + business + "/export").with(as(owner, business)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andReturn();
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"insight-studio-export-co-" + today + ".zip\"");
        Map<String, String> files = unzip(result.getResponse().getContentAsByteArray());
        assertThat(files.keySet()).containsExactlyElementsOf(ENTRIES);

        assertThat(JsonPath.<Integer>read(files.get("business.json"), "$.formatVersion")).isEqualTo(1);
        assertThat(JsonPath.<String>read(files.get("business.json"), "$.business.timeZone")).isEqualTo("Europe/Paris");
        assertThat(JsonPath.<String>read(files.get("business.json"), "$.exportedAt")).isNotBlank();

        Map<String, String> tables = Map.of("members.csv", "memberships", "invitations.csv", "invitations",
                "stores.csv", "stores", "products.csv", "products", "sales.csv", "sales", "sale_items.csv", "sale_items",
                "imports.csv", "import_batches", "saved_reports.csv", "saved_reports", "audit_events.csv", "audit_events");
        for (Map.Entry<String, String> table : tables.entrySet()) {
            assertThat(lines(files.get(table.getKey())).size() - 1).as(table.getKey()).isEqualTo(count(table.getValue()));
        }
        assertThat(JsonPath.<List<Object>>read(files.get("charts.json"), "$").size()).isEqualTo(count("chart_definitions"));
        assertThat(JsonPath.<List<Object>>read(files.get("dashboards.json"), "$").size()).isEqualTo(count("dashboards"));
        assertThat(JsonPath.<String>read(files.get("charts.json"), "$[0].definition.title")).isEqualTo("Export revenue");
        assertThat(JsonPath.<Integer>read(files.get("dashboards.json"), "$[0].layout.widgets.length()")).isEqualTo(1);

        assertThat(lines(files.get("stores.csv")).getFirst()).isEqualTo("id,code,name,city,created_at");
        assertThat(files.get("stores.csv")).contains("EXP-S1,\"EXP store, \"\"main\"\"\",Lyon,");
        // Formula injection: the product name starting with '=' is neutralized.
        assertThat(files.get("products.csv")).contains("\"'=HYPERLINK(\"\"http://evil\"\")\"").contains(",12.50,");
        assertThat(files.get("sale_items.csv")).contains(",EXP-P2,4,2.75");
        assertThat(files.get("sales.csv")).contains(",EXP-R1,2026-03-01T10:00:00Z,");
        assertThat(files.get("members.csv")).contains("owner@export.co").contains("admin@export.co");
        assertThat(files.get("invitations.csv")).contains("exp-invitee@example.com,VIEWER,open,owner");
        // The export is in its own audit file.
        assertThat(files.get("audit_events.csv")).contains("business.exported").contains("chart.created");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE business_id = ? AND action = 'business.exported'",
                Long.class, business)).isEqualTo(1);

        String everything = String.join("\n", files.values());
        List<String> forbidden = new ArrayList<>(List.of(INVITATION_SHA, OTHER_INVITATION_SHA, TestAccounts.PASSWORD,
                "SECRET-OTHER", "Secret other", "secret-other", "owner@secret-other.co"));
        forbidden.addAll(jdbc.queryForList("SELECT password_hash FROM users", String.class));
        forbidden.addAll(jdbc.queryForList("SELECT content_sha256 FROM import_batches", String.class));
        for (String secret : forbidden) {
            assertThat(everything).as("export").doesNotContain(secret);
        }
        assertThat(everything.toLowerCase()).doesNotContain("token").doesNotContain("password").doesNotContain("sha256");
    }

    @Test
    void businessExportsAreRateLimitedPerBusiness() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/businesses/" + business + "/export").with(as(owner, business))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/businesses/" + business + "/export").with(as(owner, business)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        // Another business has its own budget.
        mvc.perform(get("/api/businesses/" + other + "/export").with(as(otherOwner, other))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'business.exported' AND business_id = ?",
                Long.class, business)).isEqualTo(5);
    }

    @Test
    void theAccountExportHoldsOnlyTheAccountsOwnData() throws Exception {
        TestAccounts.TestUser both = accounts.member("both@export.co", other, Role.VIEWER);
        accounts.member(both, business, Role.OWNER);
        mvc.perform(post("/api/businesses/" + business + "/invitations").with(as(both, business))
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"friend@example.com\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated());
        createChart(business, both, "Both chart");

        MvcResult result = mvc.perform(get("/api/account/export").with(as(both, business)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/json"))
                .andReturn();
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"insight-studio-account-" + today + ".json\"");
        String json = result.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(json, "$.account.email")).isEqualTo("both@export.co");
        assertThat(JsonPath.<Integer>read(json, "$.account.id")).isEqualTo((int) both.id());
        assertThat(JsonPath.<String>read(json, "$.account.emailVerifiedAt")).isNotBlank();
        assertThat(JsonPath.<List<String>>read(json, "$.memberships[*].businessName")).containsExactly("Export Co", "Secret Other Co");
        assertThat(JsonPath.<List<String>>read(json, "$.memberships[*].role")).containsExactly("OWNER", "VIEWER");
        assertThat(JsonPath.<List<String>>read(json, "$.authored.charts[*].title")).containsExactly("Both chart");
        assertThat(JsonPath.<List<String>>read(json, "$.authored.dashboards[*].name")).containsExactly("Both chart board");
        assertThat(JsonPath.<List<Object>>read(json, "$.authored.savedReports")).isEmpty();
        assertThat(JsonPath.<List<String>>read(json, "$.auditEvents[*].action"))
                .containsExactly("member.invited", "chart.created", "dashboard.created");
        assertThat(JsonPath.<List<Integer>>read(json, "$.auditEvents[*].businessId")).containsOnly((int) business);
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.auditEvents[0].details")).isEqualTo(Map.of("role", "VIEWER"));
        // Nothing secret, nothing of other people beyond business names.
        List<String> forbidden = new ArrayList<>(jdbc.queryForList("SELECT password_hash FROM users", String.class));
        forbidden.addAll(jdbc.queryForList("SELECT token_sha256 FROM invitations", String.class));
        forbidden.addAll(List.of("friend@example.com", "owner@export.co", "owner@secret-other.co", "SECRET-OTHER", "EXP-S1"));
        for (String secret : forbidden) {
            assertThat(json).doesNotContain(secret);
        }
        assertThat(json.toLowerCase()).doesNotContain("password").doesNotContain("session").doesNotContain("token");

        for (int i = 0; i < 4; i++) {
            mvc.perform(get("/api/account/export").with(as(both, business))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/account/export").with(as(both, business))).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/account/export").with(as(owner, business))).andExpect(status().isOk());
    }
}
