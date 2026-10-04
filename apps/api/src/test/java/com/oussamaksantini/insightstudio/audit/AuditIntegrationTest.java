package com.oussamaksantini.insightstudio.audit;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.CapturingInvitationNotifier;
import com.oussamaksantini.insightstudio.testsupport.CapturingPasswordResetNotifier;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Audit history (docs/account-management-contract.md §2): every audited action, performed through the
 * API, writes exactly its event with its allowlisted details; the history is ADMIN+, newest first,
 * paged and filtered by category, and never shows another business's events. Finally every stored
 * {@code details} is scanned: no password, hash, token, link, email body or file content anywhere.
 */
class AuditIntegrationTest extends PostgresIntegrationTest {

    private static final String CSV = """
            store_code,receipt_number,sold_at,sku,quantity,unit_price
            S1,R-SECRET-ROW-1,2026-06-05T10:00:00Z,SKU-1,1,5.00
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CapturingInvitationNotifier invitations;

    @Autowired
    CapturingPasswordResetNotifier resets;

    SqlFixture db;
    TestAccounts accounts;
    long business;
    long other;
    TestUser owner;
    TestUser admin;
    TestUser viewer;
    TestUser outsiderOwner;
    String invitationToken;

    @BeforeEach
    void setUp() {
        db = new SqlFixture(jdbc);
        db.clear();
        invitations.clear();
        resets.clear();
        accounts = new TestAccounts(jdbc);
        business = db.business("Audit Co", "audit-co", "EUR", "UTC");
        other = db.business("Other Co", "other-co", "EUR", "UTC");
        db.store(business, "S1", "Store", null);
        db.product(business, "SKU-1", "Widget", "Misc", "5.00");
        owner = accounts.member("owner@audit.co", business, Role.OWNER);
        admin = accounts.member("admin@audit.co", business, Role.ADMIN);
        viewer = accounts.member("viewer@audit.co", business, Role.VIEWER);
        outsiderOwner = accounts.member("owner@other.co", other, Role.OWNER);
    }

    private ResultActions json(MockHttpServletRequestBuilder request, TestUser user, long businessId, String body)
            throws Exception {
        return mvc.perform(request.with(as(user, businessId)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long id(ResultActions result, String path) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), path)).longValue();
    }

    private List<Map<String, Object>> events(long businessId) {
        return jdbc.queryForList("""
                SELECT action, actor_user_id, target_type, target_id, details::text AS details
                FROM audit_events WHERE business_id = ? ORDER BY id
                """, businessId);
    }

    private static String chart(String title) {
        return """
                {"schemaVersion": 1, "title": "%s", "visualization": "bar", "metrics": ["revenue"], "groupBy": "store",
                 "granularity": null, "range": {"type": "fixed", "from": "2026-04-01", "to": "2026-05-31"},
                 "filters": {"storeIds": [], "categories": [], "productIds": []}, "limit": 10, "engine": "sql"}
                """.formatted(title);
    }

    private static String layout(long chart) {
        return """
                {"schemaVersion": 1, "widgets": [{"id": "only", "chartId": %d}],
                 "desktop": {"columns": 12, "items": [{"id": "only", "x": 0, "y": 0, "w": 12, "h": 6}]},
                 "mobile": {"columns": 4, "items": [{"id": "only", "x": 0, "y": 0, "w": 4, "h": 6}]}}
                """.formatted(chart);
    }

    /** Performs every audited action once (and a few that are not audited). */
    private void exerciseEveryAction() throws Exception {
        String b = "/api/businesses/" + business;
        // business.*
        long created = id(json(post("/api/businesses"), owner, business,
                "{\"name\":\"Fresh Co\",\"currency\":\"GBP\",\"timeZone\":\"Europe/London\"}").andExpect(status().isCreated()),
                "$.businessId");
        json(patch(b), owner, business, "{\"name\":\"Audit Co Renamed\"}").andExpect(status().isOk());
        json(patch(b), owner, business, "{\"timeZone\":\"Europe/Paris\"}").andExpect(status().isOk());
        json(patch("/api/businesses/" + created), owner, created, "{\"currency\":\"USD\"}").andExpect(status().isOk());
        mvc.perform(get(b + "/export").with(as(owner, business))).andExpect(status().isOk());

        // member.* and invitation.*
        long invitation = id(json(post(b + "/invitations"), admin, business, "{\"email\":\"joiner@audit.co\",\"role\":\"VIEWER\"}")
                .andExpect(status().isCreated()), "$.id");
        invitationToken = invitations.last().token();
        long revoked = id(json(post(b + "/invitations"), owner, business, "{\"email\":\"never@audit.co\",\"role\":\"ADMIN\"}")
                .andExpect(status().isCreated()), "$.id");
        mvc.perform(delete(b + "/invitations/" + revoked).with(as(owner, business))).andExpect(status().isNoContent());
        TestUser joiner = accounts.user("joiner@audit.co");
        json(post("/api/invitations/accept"), joiner, business, "{\"token\":\"" + invitationToken + "\"}").andExpect(status().isOk());
        json(patch(b + "/members/" + joiner.id()), owner, business, "{\"role\":\"ADMIN\"}").andExpect(status().isOk());
        mvc.perform(delete(b + "/members/" + joiner.id()).with(as(owner, business))).andExpect(status().isNoContent());
        mvc.perform(delete(b + "/members/" + admin.id()).with(as(admin, business))).andExpect(status().isNoContent());
        // A password reset creates a token (and a link) that must never reach the audit history.
        json(post("/api/auth/password/forgot"), viewer, business, "{\"email\":\"viewer@audit.co\"}").andExpect(status().isAccepted());
        json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/account"), viewer, business,
                "{\"password\":\"" + TestAccounts.PASSWORD + "\",\"confirmEmail\":\"VIEWER@audit.co\"}")
                .andExpect(status().isNoContent());

        // import.*
        MockMultipartFile file = new MockMultipartFile("file", "june-sales.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/imports").file(file).param("dryRun", "false").with(as(owner, business)))
                .andExpect(status().isOk());
        // The same file again is rejected (already imported); a dry run is not audited.
        mvc.perform(multipart("/api/imports").file(file).param("dryRun", "false").with(as(owner, business)));
        mvc.perform(multipart("/api/imports").file(file).param("dryRun", "true").with(as(owner, business)));

        // chart.*
        long chart = id(json(post("/api/charts"), owner, business, chart("Revenue")).andExpect(status().isCreated()), "$.id");
        json(put("/api/charts/" + chart), owner, business,
                "{\"definition\": " + chart("Revenue by store") + ", \"expectedRevision\": 1}").andExpect(status().isOk());
        long copy = id(mvc.perform(post("/api/charts/" + chart + "/duplicate").with(as(owner, business)))
                .andExpect(status().isCreated()), "$.id");
        mvc.perform(delete("/api/charts/" + copy).with(as(owner, business))).andExpect(status().isNoContent());
        mvc.perform(get("/api/charts/" + chart + "/data").with(as(owner, business))).andExpect(status().isOk());

        // dashboard.*
        long dashboard = id(json(post("/api/dashboards"), owner, business,
                "{\"name\": \"Overview\", \"layout\": " + layout(chart) + "}").andExpect(status().isCreated()), "$.id");
        json(put("/api/dashboards/" + dashboard), owner, business,
                "{\"name\": \"Main overview\", \"layout\": " + layout(chart) + ", \"expectedRevision\": 1}")
                .andExpect(status().isOk());
        long dashboardCopy = id(mvc.perform(post("/api/dashboards/" + dashboard + "/duplicate").with(as(owner, business)))
                .andExpect(status().isCreated()), "$.id");
        mvc.perform(delete("/api/dashboards/" + dashboardCopy).with(as(owner, business))).andExpect(status().isNoContent());

        // The other business has an event of its own.
        json(patch("/api/businesses/" + other), outsiderOwner, other, "{\"name\":\"Other Renamed\"}").andExpect(status().isOk());
        assertThat(invitation).isPositive();
    }

    @Test
    void everyActionWritesItsEventWithAllowlistedDetails() throws Exception {
        exerciseEveryAction();

        Set<String> all = new TreeSet<>(jdbc.queryForList("SELECT DISTINCT action FROM audit_events", String.class));
        // billing.plan_changed is written by the billing worker: BillingWebhookIntegrationTest covers it.
        assertThat(all).containsExactlyInAnyOrderElementsOf(Arrays.stream(AuditAction.values())
                .filter(a -> a != AuditAction.BILLING_PLAN_CHANGED).map(AuditAction::action).collect(Collectors.toSet()));

        List<Map<String, Object>> events = events(business);
        assertThat(events).extracting(e -> e.get("action")).containsExactly(
                "business.renamed", "business.time_zone_changed", "business.exported",
                "member.invited", "member.invited", "invitation.revoked", "invitation.accepted", "member.role_changed",
                "member.removed", "member.left", "member.account_deleted",
                "import.completed", "import.rejected",
                "chart.created", "chart.updated", "chart.duplicated", "chart.deleted",
                "dashboard.created", "dashboard.updated", "dashboard.duplicated", "dashboard.deleted");
        assertThat(details(events, "business.renamed")).isEqualTo(Map.of("from", "Audit Co", "to", "Audit Co Renamed"));
        assertThat(details(events, "business.time_zone_changed")).isEqualTo(Map.of("from", "UTC", "to", "Europe/Paris"));
        assertThat(details(events, "business.exported")).isEmpty();
        assertThat(details(events, "member.invited")).isEqualTo(Map.of("email", "joiner@audit.co", "role", "VIEWER"));
        assertThat(details(events, "invitation.revoked")).isEqualTo(Map.of("email", "never@audit.co", "role", "ADMIN"));
        assertThat(details(events, "invitation.accepted")).isEqualTo(Map.of("role", "VIEWER"));
        assertThat(details(events, "member.role_changed")).isEqualTo(Map.of("from", "VIEWER", "to", "ADMIN"));
        assertThat(details(events, "member.removed")).isEqualTo(Map.of("role", "ADMIN"));
        assertThat(details(events, "member.left")).isEqualTo(Map.of("role", "ADMIN"));
        assertThat(details(events, "member.account_deleted")).isEqualTo(Map.of("role", "VIEWER"));
        assertThat(details(events, "import.completed")).isEqualTo(Map.of("kind", "sales", "mode", "create_only",
                "fileName", "june-sales.csv", "rows", 1, "created", 1, "updated", 0, "errors", 0));
        assertThat(details(events, "import.rejected")).containsEntry("errors", 1).containsEntry("created", 0);
        assertThat(details(events, "chart.created")).isEqualTo(Map.of("title", "Revenue", "revision", 1));
        assertThat(details(events, "chart.updated")).isEqualTo(Map.of("title", "Revenue by store", "revision", 2));
        assertThat(details(events, "chart.duplicated")).containsEntry("title", "Copy of Revenue by store").containsKey("fromChartId");
        assertThat(details(events, "chart.deleted")).isEqualTo(Map.of("title", "Copy of Revenue by store", "revision", 1));
        assertThat(details(events, "dashboard.created")).isEqualTo(Map.of("name", "Overview", "revision", 1, "widgetCount", 1));
        assertThat(details(events, "dashboard.updated")).isEqualTo(
                Map.of("name", "Main overview", "revision", 2, "widgetCount", 1, "renamedFrom", "Overview"));
        assertThat(details(events, "dashboard.duplicated")).isEqualTo(
                Map.of("name", "Copy of Main overview", "revision", 1, "widgetCount", 1));
        assertThat(events.stream().filter(e -> e.get("action").equals("member.invited")).map(e -> e.get("target_type")))
                .containsOnly("invitation");
        assertThat(events.stream().filter(e -> e.get("action").toString().startsWith("chart.")).map(e -> e.get("target_type")))
                .containsOnly("chart");

        Map<String, Object> created = jdbc.queryForMap("""
                SELECT e.action, e.details::text AS details, e.actor_user_id FROM audit_events e
                JOIN businesses b ON b.id = e.business_id WHERE b.slug = 'fresh-co' AND e.action = 'business.created'
                """);
        assertThat(JsonPath.<Map<String, Object>>read(created.get("details").toString(), "$"))
                .isEqualTo(Map.of("name", "Fresh Co", "currency", "GBP", "timeZone", "Europe/London"));
        assertThat(((Number) created.get("actor_user_id")).longValue()).isEqualTo(owner.id());

        // The API: newest first, actors named, the deleted account shown as such.
        String page = mvc.perform(get("/api/businesses/" + business + "/audit").with(as(owner, business)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(page, "$.events[0].action")).isEqualTo("dashboard.deleted");
        assertThat(JsonPath.<String>read(page, "$.events[0].actor.name")).isEqualTo("owner");
        assertThat(JsonPath.<Integer>read(page, "$.events.length()")).isEqualTo(events.size());
        assertThat((Object) JsonPath.read(page, "$.nextBefore")).isNull();
        List<Map<String, Object>> deletedActor = JsonPath.read(page, "$.events[?(@.action == 'member.account_deleted')].actor");
        assertThat(deletedActor.getFirst()).containsEntry("name", "Deleted account").doesNotContainKey("email");
        assertThat(page).doesNotContain("viewer@audit.co").doesNotContain("\"email\":\"owner@audit.co\"");

        assertNoSecretsInAnyDetails(invitationToken);
    }

    @Test
    void historyIsPagedFilteredAndScopedToItsBusiness() throws Exception {
        exerciseEveryAction();
        String path = "/api/businesses/" + business + "/audit";
        List<String> seen = new ArrayList<>();
        Long before = null;
        do {
            MockHttpServletRequestBuilder request = get(path).param("limit", "4").with(as(owner, business));
            if (before != null) {
                request.param("before", before.toString());
            }
            String json = mvc.perform(request)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.read(json, "$.events[*].action"));
            Number next = JsonPath.read(json, "$.nextBefore");
            before = next == null ? null : next.longValue();
        } while (before != null);
        List<String> expected = new ArrayList<>(events(business).stream().map(e -> e.get("action").toString()).toList());
        java.util.Collections.reverse(expected);
        assertThat(seen).isEqualTo(expected);

        String members = mvc.perform(get(path).param("category", "member").with(as(owner, business)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(members, "$.events[*].action"))
                .isNotEmpty().allMatch(a -> a.startsWith("member.") || a.startsWith("invitation."));
        for (String category : List.of("business", "import", "chart", "dashboard")) {
            String json = mvc.perform(get(path).param("category", category).with(as(owner, business)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(json, "$.events[*].action")).isNotEmpty().allMatch(a -> a.startsWith(category + "."));
        }
        mvc.perform(get(path).param("category", "secrets").with(as(owner, business))).andExpect(status().isBadRequest());
        mvc.perform(get(path).param("limit", "0").with(as(owner, business))).andExpect(status().isBadRequest());
        mvc.perform(get(path).param("limit", "101").with(as(owner, business))).andExpect(status().isBadRequest());

        // Isolation: the other business's history has only its own event, and neither owner sees the other's.
        String others = mvc.perform(get("/api/businesses/" + other + "/audit").with(as(outsiderOwner, other)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(others, "$.events[*].action")).containsExactly("business.renamed");
        mvc.perform(get("/api/businesses/" + other + "/audit").with(as(owner, other))).andExpect(status().isNotFound());
        mvc.perform(get(path).with(as(outsiderOwner, business))).andExpect(status().isNotFound());
    }

    /** Every value that must never appear in any audit event's details. */
    private void assertNoSecretsInAnyDetails(String invitationToken) {
        List<String> details = jdbc.queryForList("SELECT details::text FROM audit_events", String.class);
        assertThat(details).isNotEmpty();
        List<String> forbidden = new ArrayList<>();
        forbidden.add(TestAccounts.PASSWORD);
        forbidden.add(invitationToken);
        forbidden.addAll(jdbc.queryForList("SELECT password_hash FROM users WHERE password_hash IS NOT NULL", String.class));
        forbidden.addAll(jdbc.queryForList("SELECT token_sha256 FROM invitations", String.class));
        forbidden.addAll(jdbc.queryForList("SELECT token_sha256 FROM password_reset_tokens", String.class));
        forbidden.addAll(jdbc.queryForList("SELECT token_sha256 FROM email_verification_tokens", String.class));
        forbidden.addAll(jdbc.queryForList("SELECT content_sha256 FROM import_batches", String.class));
        forbidden.addAll(jdbc.queryForList("SELECT primary_id FROM spring_session", String.class));
        resets.sent().forEach(sent -> {
            forbidden.add(sent.token());
            forbidden.add(sent.link());
        });
        invitations.sent().forEach(sent -> forbidden.add(sent.link()));
        forbidden.add("R-SECRET-ROW-1");
        forbidden.add("store_code,receipt_number");
        for (String detail : details) {
            for (String secret : forbidden) {
                assertThat(detail).as("audit details").doesNotContain(secret);
            }
            assertThat(detail.toLowerCase()).doesNotContain("http://").doesNotContain("https://").doesNotContain("token")
                    .doesNotContain("password").doesNotContain("hash").doesNotContain("body").doesNotContain("cookie");
        }
        assertThat(forbidden).hasSizeGreaterThan(8);
    }

    private static Map<String, Object> details(List<Map<String, Object>> events, String action) {
        return events.stream().filter(e -> e.get("action").equals(action)).findFirst()
                .map(e -> JsonPath.<Map<String, Object>>read(e.get("details").toString(), "$"))
                .orElseThrow(() -> new AssertionError("no " + action));
    }
}
