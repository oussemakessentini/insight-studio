package com.oussamaksantini.insightstudio.accountdata;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.cubepurge.CubePurgeWorker;
import com.oussamaksantini.insightstudio.mail.MailOutbox;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.CapturingPasswordResetNotifier;
import com.oussamaksantini.insightstudio.testsupport.CapturingVerificationNotifier;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Business and account deletion end to end (docs/account-management-contract.md §4): previews,
 * reauthentication, typed confirmations, everything of the business gone and nothing of another
 * business touched, pending emails expired with their bodies erased, the Cube purge queued; account
 * tombstones, every session ended, last-owner protection, and signing up again with the address.
 */
class DeletionIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MailOutbox outbox;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    CubePurgeWorker purges;

    @Autowired
    CapturingPasswordResetNotifier resets;

    @Autowired
    CapturingVerificationNotifier verifications;

    @LocalServerPort
    int port;

    SqlFixture db;
    TestAccounts accounts;
    long doomed;
    long kept;
    TestUser owner;
    TestUser admin;
    TestUser keptOwner;

    @BeforeEach
    void setUp() throws Exception {
        db = new SqlFixture(jdbc);
        db.clear();
        resets.clear();
        verifications.clear();
        accounts = new TestAccounts(jdbc);
        doomed = db.business("Doomed Co", "doomed-co", "EUR", "Asia/Tokyo");
        kept = db.business("Kept Co", "kept-co", "EUR", "UTC");
        owner = accounts.member("owner@doomed.co", doomed, Role.OWNER);
        admin = accounts.member("admin@doomed.co", doomed, Role.ADMIN);
        keptOwner = accounts.member("owner@kept.co", kept, Role.OWNER);
        // The admin of the doomed business is also a member of the kept one.
        accounts.member(admin, kept, Role.VIEWER);
        fill(doomed, owner, "D");
        fill(kept, keptOwner, "K");
    }

    /** Every kind of business data, created partly through the API so that audit events exist too. */
    private void fill(long businessId, TestUser user, String prefix) throws Exception {
        long store = db.store(businessId, prefix + "-S1", "Store", null);
        long product = db.product(businessId, prefix + "-P1", "Widget", "Misc", "4.00");
        long sale = db.sale(store, prefix + "-R1", "2026-03-01T10:00:00Z", product, 2, "4.00");
        db.importBatch(businessId, prefix + ".csv", "8.00", sale);
        jdbc.update("""
                INSERT INTO saved_reports (business_id, name, kind, range_type, relative_preset, created_by)
                VALUES (?, ?, 'monthly', 'relative', 'last_30_days', ?)
                """, businessId, prefix + " report", user.id());
        String chart = """
                {"schemaVersion": 1, "title": "%s chart", "visualization": "bar", "metrics": ["revenue"], "groupBy": "store",
                 "granularity": null, "range": {"type": "fixed", "from": "2026-03-01", "to": "2026-03-31"},
                 "filters": {"storeIds": [], "categories": [], "productIds": []}, "limit": 10, "engine": "sql"}
                """.formatted(prefix);
        long chartId = id(json(post("/api/charts"), user, businessId, chart).andExpect(status().isCreated()), "$.id");
        json(post("/api/dashboards"), user, businessId, """
                {"name": "%s board", "layout": {"schemaVersion": 1, "widgets": [{"id": "only", "chartId": %d}],
                 "desktop": {"columns": 12, "items": [{"id": "only", "x": 0, "y": 0, "w": 12, "h": 6}]},
                 "mobile": {"columns": 4, "items": [{"id": "only", "x": 0, "y": 0, "w": 4, "h": 6}]}}}
                """.formatted(prefix, chartId)).andExpect(status().isCreated());
        json(post("/api/businesses/" + businessId + "/invitations"), user, businessId,
                "{\"email\":\"" + prefix.toLowerCase() + "-invitee@example.com\",\"role\":\"VIEWER\"}").andExpect(status().isCreated());
        // A pending invitation email of the business, as the real notifier queues it.
        transactions.executeWithoutResult(status -> outbox.enqueue("invitation", prefix + "-invitee@example.com",
                "You're invited", "Open https://example.test/invite?token=" + prefix + "-SECRET", Instant.now().plusSeconds(3600),
                businessId, null));
    }

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, TestUser user,
            long businessId, String body) throws Exception {
        return mvc.perform(request.with(as(user, businessId)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long id(ResultActions result, String path) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), path)).longValue();
    }

    private ResultActions deleteBusiness(TestUser user, long businessId, String password, String confirmName) throws Exception {
        return json(delete("/api/businesses/" + businessId), user, businessId,
                "{\"password\":\"%s\",\"confirmName\":\"%s\"}".formatted(password, confirmName));
    }

    private Map<String, Long> rowsOf(long businessId) {
        Map<String, Long> rows = new LinkedHashMap<>();
        for (String table : AccountDataQueries.BUSINESS_TABLES) {
            rows.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE business_id = ?", Long.class, businessId));
        }
        rows.put("businesses", jdbc.queryForObject("SELECT COUNT(*) FROM businesses WHERE id = ?", Long.class, businessId));
        return rows;
    }

    // ---------------------------------------------------------------- business

    @Test
    void theDeletionPreviewCountsEverything() throws Exception {
        mvc.perform(get("/api/businesses/" + doomed + "/deletion-preview").with(as(owner, doomed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.id").value(doomed))
                .andExpect(jsonPath("$.business.name").value("Doomed Co"))
                .andExpect(jsonPath("$.counts.members").value(2))
                .andExpect(jsonPath("$.counts.pendingInvitations").value(1))
                .andExpect(jsonPath("$.counts.stores").value(1))
                .andExpect(jsonPath("$.counts.products").value(1))
                .andExpect(jsonPath("$.counts.sales").value(1))
                .andExpect(jsonPath("$.counts.imports").value(1))
                .andExpect(jsonPath("$.counts.savedReports").value(1))
                .andExpect(jsonPath("$.counts.charts").value(1))
                .andExpect(jsonPath("$.counts.dashboards").value(1))
                .andExpect(jsonPath("$.counts.auditEvents").value(3))
                .andExpect(jsonPath("$.otherMembers.length()").value(1))
                .andExpect(jsonPath("$.otherMembers[0].userId").value(admin.id()))
                .andExpect(jsonPath("$.otherMembers[0].displayName").value("admin"))
                .andExpect(jsonPath("$.otherMembers[0].role").value("ADMIN"));
    }

    @Test
    void deletingABusinessRemovesAllOfItAndNothingElse() throws Exception {
        Map<String, Long> keptBefore = rowsOf(kept);
        assertThat(keptBefore.values()).allMatch(n -> n > 0);
        assertThat(rowsOf(doomed).values()).allMatch(n -> n > 0);

        deleteBusiness(owner, doomed, TestAccounts.PASSWORD, "doomed co").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Type the business name exactly as shown to confirm."));
        deleteBusiness(owner, doomed, "wrong password", "Doomed Co").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Your password is incorrect."));
        deleteBusiness(admin, doomed, TestAccounts.PASSWORD, "Doomed Co").andExpect(status().isForbidden());
        deleteBusiness(keptOwner, doomed, TestAccounts.PASSWORD, "Doomed Co").andExpect(status().isNotFound());
        assertThat(rowsOf(doomed).get("businesses")).isEqualTo(1);

        long versionBefore = jdbc.queryForObject("SELECT version FROM report_data_version", Long.class);
        // Surrounding whitespace in the typed name is ignored.
        deleteBusiness(owner, doomed, TestAccounts.PASSWORD, "  Doomed Co ").andExpect(status().isNoContent());

        assertThat(rowsOf(doomed).values()).allMatch(n -> n == 0);
        assertThat(rowsOf(kept)).isEqualTo(keptBefore);
        Map<String, Object> mail = jdbc.queryForMap("SELECT status, body, finished_at FROM mail_outbox WHERE business_id = ?", doomed);
        assertThat(mail.get("status")).isEqualTo("EXPIRED");
        assertThat(mail.get("body")).isNull();
        assertThat(mail.get("finished_at")).isNotNull();
        Map<String, Object> keptMail = jdbc.queryForMap("SELECT status, body FROM mail_outbox WHERE business_id = ?", kept);
        assertThat(keptMail.get("status")).isEqualTo("PENDING");
        assertThat(keptMail.get("body")).isNotNull();

        long versionAfter = jdbc.queryForObject("SELECT version FROM report_data_version", Long.class);
        assertThat(versionAfter).isGreaterThan(versionBefore);
        Map<String, Object> purge = jdbc.queryForMap("SELECT * FROM cube_purge_requests");
        assertThat(((Number) purge.get("business_id")).longValue()).isEqualTo(doomed);
        assertThat(purge.get("time_zone")).isEqualTo("Asia/Tokyo");
        assertThat(((Number) purge.get("data_version")).longValue()).isEqualTo(versionAfter);
        assertThat(purge.get("status")).isEqualTo("PENDING");

        // The other member's session stays valid, but the business is gone for them.
        mvc.perform(get("/api/businesses").with(as(admin, kept)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].businessId").value(kept));
        mvc.perform(get("/api/businesses/" + doomed + "/settings").with(as(admin, kept))).andExpect(status().isNotFound());
        mvc.perform(get("/api/dashboard/context").with(as(admin, doomed))).andExpect(status().isNotFound());
        deleteBusiness(owner, doomed, TestAccounts.PASSWORD, "Doomed Co").andExpect(status().isNotFound());
        // The owner's account is untouched.
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM users WHERE id = ?", Boolean.class, owner.id())).isTrue();

        // No Cube in this application: the purge is skipped.
        assertThat(purges.processDue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM cube_purge_requests", String.class)).isEqualTo("SKIPPED");
    }

    @Test
    void wrongPasswordsAreRateLimitedLikePasswordChanges() throws Exception {
        for (int i = 0; i < 5; i++) {
            deleteBusiness(owner, doomed, "wrong " + i, "Doomed Co").andExpect(status().isBadRequest());
        }
        deleteBusiness(owner, doomed, TestAccounts.PASSWORD, "Doomed Co")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value("Too many attempts. Try again later."));
        json(delete("/api/account"), owner, doomed,
                "{\"password\":\"%s\",\"confirmEmail\":\"owner@doomed.co\"}".formatted(TestAccounts.PASSWORD))
                .andExpect(status().isTooManyRequests());
        assertThat(rowsOf(doomed).get("businesses")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- account

    @Test
    void theLastOwnerCannotDeleteTheAccount() throws Exception {
        mvc.perform(get("/api/account/deletion-preview").with(as(owner, doomed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.email").value("owner@doomed.co"))
                .andExpect(jsonPath("$.account.displayName").value("owner"))
                .andExpect(jsonPath("$.memberships[0].businessId").value(doomed))
                .andExpect(jsonPath("$.memberships[0].role").value("OWNER"))
                .andExpect(jsonPath("$.memberships[0].memberCount").value(2))
                .andExpect(jsonPath("$.blockingBusinesses.length()").value(1))
                .andExpect(jsonPath("$.blockingBusinesses[0].businessName").value("Doomed Co"))
                .andExpect(jsonPath("$.authoredContent.charts").value(1))
                .andExpect(jsonPath("$.authoredContent.dashboards").value(1))
                .andExpect(jsonPath("$.authoredContent.savedReports").value(1))
                .andExpect(jsonPath("$.authoredContent.imports").value(0));
        json(delete("/api/account"), owner, doomed,
                "{\"password\":\"%s\",\"confirmEmail\":\"owner@doomed.co\"}".formatted(TestAccounts.PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "You are the only owner of Doomed Co. Make another member an owner or delete the business first."));
        json(delete("/api/account"), owner, doomed,
                "{\"password\":\"wrong\",\"confirmEmail\":\"owner@doomed.co\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Your password is incorrect."));
        json(delete("/api/account"), owner, doomed,
                "{\"password\":\"%s\",\"confirmEmail\":\"someone@doomed.co\"}".formatted(TestAccounts.PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Type your email address to confirm."));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM users WHERE id = ?", Boolean.class, owner.id())).isTrue();
        assertThat(db.count("memberships")).isEqualTo(4);
    }

    private static void expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("%s %s -> %s", response.request().method(), response.request().uri(),
                response.body()).isEqualTo(status);
    }

    private HttpApiClient signIn(String email, String password) throws Exception {
        HttpApiClient client = new HttpApiClient(port);
        client.get("/api/session");
        expect(client.postJson("/api/auth/sign-in", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)), 200);
        return client;
    }

    @Test
    void deletingAnAccountEndsEverySessionAndLeavesATombstone() throws Exception {
        // The admin: member of both businesses, author of a chart in the kept one, with tokens, pending
        // mail and an open invitation to its address in a third business.
        long third = db.business("Third Co", "third-co", "EUR", "UTC");
        TestUser thirdOwner = accounts.member("owner@third.co", third, Role.OWNER);
        json(post("/api/businesses/" + third + "/invitations"), thirdOwner, third,
                "{\"email\":\"ADMIN@doomed.co\",\"role\":\"VIEWER\"}").andExpect(status().isCreated());
        jdbc.update("UPDATE memberships SET role = 'ADMIN' WHERE user_id = ? AND business_id = ?", admin.id(), kept);
        String chart = """
                {"schemaVersion": 1, "title": "Admin chart", "visualization": "bar", "metrics": ["revenue"], "groupBy": "store",
                 "granularity": null, "range": {"type": "fixed", "from": "2026-03-01", "to": "2026-03-31"},
                 "filters": {"storeIds": [], "categories": [], "productIds": []}, "limit": 10, "engine": "sql"}
                """;
        json(post("/api/charts"), admin, kept, chart).andExpect(status().isCreated());
        // Invitations the admin sent: two open ones (with their pending emails), one already accepted.
        json(post("/api/businesses/" + kept + "/invitations"), admin, kept,
                "{\"email\":\"sent-1@example.com\",\"role\":\"VIEWER\"}").andExpect(status().isCreated());
        json(post("/api/businesses/" + doomed + "/invitations"), admin, doomed,
                "{\"email\":\"sent-2@example.com\",\"role\":\"ADMIN\"}").andExpect(status().isCreated());
        jdbc.update("""
                INSERT INTO invitations (business_id, email, role, token_sha256, invited_by, expires_at, accepted_at, accepted_by)
                VALUES (?, 'accepted@example.com', 'VIEWER', ?, ?, now() + interval '1 day', now(), ?)
                """, kept, "e".repeat(64), admin.id(), keptOwner.id());
        transactions.executeWithoutResult(status -> {
            outbox.enqueue("invitation", "sent-1@example.com", "Invited", "Open https://example.test/invite?token=S1",
                    Instant.now().plusSeconds(600), kept, null);
            outbox.enqueue("invitation", "Sent-2@example.com", "Invited", "Open https://example.test/invite?token=S2",
                    Instant.now().plusSeconds(600), doomed, null);
        });
        mvc.perform(get("/api/account/deletion-preview").with(as(admin, kept)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openInvitationsSent").value(2));
        transactions.executeWithoutResult(status -> {
            outbox.enqueue("password reset", "admin@doomed.co", "Reset", "Open https://example.test/reset?token=SECRET",
                    Instant.now().plusSeconds(600), null, admin.id());
            outbox.enqueue("notice", "Admin@Doomed.co", "Notice", "Body for the address", null);
        });
        jdbc.update("INSERT INTO password_reset_tokens (user_id, token_sha256, expires_at) VALUES (?, ?, now() + interval '1 hour')",
                admin.id(), "c".repeat(64));
        jdbc.update("INSERT INTO email_verification_tokens (user_id, token_sha256, expires_at) VALUES (?, ?, now() + interval '1 day')",
                admin.id(), "d".repeat(64));

        try (HttpApiClient laptop = signIn("admin@doomed.co", TestAccounts.PASSWORD);
                HttpApiClient phone = signIn("admin@doomed.co", TestAccounts.PASSWORD)) {
            expect(phone.get("/api/businesses"), 200);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spring_session WHERE principal_name = ?", Long.class,
                    "user:" + admin.id())).isGreaterThanOrEqualTo(2);
            int versionBefore = jdbc.queryForObject("SELECT session_version FROM users WHERE id = ?", Integer.class, admin.id());

            expect(laptop.deleteJson("/api/account",
                    "{\"password\":\"%s\",\"confirmEmail\":\" Admin@Doomed.co \"}".formatted(TestAccounts.PASSWORD)), 204);
            assertThat(laptop.cookie(HttpApiClient.SESSION_COOKIE)).isNull();

            // Every session is gone: both browsers are signed out on their next request.
            expect(phone.get("/api/businesses"), 401);
            expect(laptop.get("/api/businesses"), 401);
            assertThat((Boolean) JsonPath.read(phone.get("/api/session").body(), "$.authenticated")).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spring_session WHERE principal_name = ?", Long.class,
                    "user:" + admin.id())).isZero();

            Map<String, Object> tombstone = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", admin.id());
            assertThat(tombstone.get("email")).isNull();
            assertThat(tombstone.get("password_hash")).isNull();
            assertThat(tombstone.get("email_verified_at")).isNull();
            assertThat(tombstone.get("last_sign_in_at")).isNull();
            assertThat(tombstone.get("display_name")).isEqualTo("Deleted account");
            assertThat(tombstone.get("deleted_at")).isNotNull();
            assertThat((Integer) tombstone.get("session_version")).isEqualTo(versionBefore + 1);
        }
        // A session principal an instance might still hold is rejected too (stale session version, no user).
        mvc.perform(get("/api/businesses").with(as(admin, kept))).andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE user_id = ?", Long.class, admin.id())).isZero();
        assertThat(jdbc.queryForList("SELECT business_id FROM audit_events WHERE action = 'member.account_deleted' ORDER BY business_id",
                Long.class)).containsExactly(doomed, kept);
        assertThat(jdbc.queryForObject("""
                SELECT revoked_at IS NOT NULL FROM invitations WHERE business_id = ? AND lower(email) = 'admin@doomed.co'
                """, Boolean.class, third)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ?", Long.class, admin.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_tokens WHERE user_id = ?", Long.class, admin.id())).isZero();
        assertThat(jdbc.queryForList("""
                SELECT status || ':' || (body IS NULL) FROM mail_outbox
                WHERE user_id = ? OR lower(recipient) = 'admin@doomed.co' ORDER BY id
                """, String.class, admin.id())).containsExactly("EXPIRED:true", "EXPIRED:true");
        // The invitations it sent are revoked (the accepted one is left as it was), with their events written
        // by the account before it became a tombstone, and their emails are cancelled.
        assertThat(jdbc.queryForList("""
                SELECT email || ':' || (revoked_at IS NOT NULL) FROM invitations WHERE invited_by = ? ORDER BY email
                """, String.class, admin.id())).containsExactly("accepted@example.com:false", "sent-1@example.com:true",
                "sent-2@example.com:true");
        assertThat(jdbc.queryForList("""
                SELECT business_id || ':' || (details ->> 'email') || ':' || (details ->> 'role') FROM audit_events
                WHERE action = 'invitation.revoked' AND actor_user_id = ? ORDER BY business_id
                """, String.class, admin.id())).containsExactly(doomed + ":sent-2@example.com:ADMIN",
                kept + ":sent-1@example.com:VIEWER");
        assertThat(jdbc.queryForList("""
                SELECT status || ':' || (body IS NULL) FROM mail_outbox WHERE lower(recipient) LIKE 'sent-%' ORDER BY id
                """, String.class)).containsExactly("EXPIRED:true", "EXPIRED:true");
        String open = mvc.perform(get("/api/businesses/" + kept + "/invitations").with(as(keptOwner, kept)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(open, "$[*].email")).doesNotContain("sent-1@example.com");
        // Other people's pending emails are untouched.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mail_outbox WHERE status = 'PENDING'", Long.class)).isEqualTo(2);

        // What the account created stays with its business, shown as "Deleted account".
        String charts = mvc.perform(get("/api/charts").with(as(keptOwner, kept))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(charts, "$[?(@.title == 'Admin chart')].updatedBy")).containsExactly("Deleted account");
        mvc.perform(get("/api/businesses/" + kept + "/members").with(as(keptOwner, kept)))
                .andExpect(jsonPath("$.length()").value(1));
        String audit = mvc.perform(get("/api/businesses/" + kept + "/audit").with(as(keptOwner, kept)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(audit, "$.events[?(@.actor.id == " + admin.id() + ")].actor.name"))
                .isNotEmpty().containsOnly("Deleted account");

        // The address is free: no sign-in, no reset, and a new, unrelated account can be created.
        try (HttpApiClient browser = new HttpApiClient(port)) {
            browser.get("/api/session");
            expect(browser.postJson("/api/auth/sign-in",
                    "{\"email\":\"admin@doomed.co\",\"password\":\"%s\"}".formatted(TestAccounts.PASSWORD)), 401);
            expect(browser.postJson("/api/auth/password/forgot", "{\"email\":\"admin@doomed.co\"}"), 202);
            assertThat(resets.sent()).isEmpty();
            expect(browser.postJson("/api/auth/sign-up",
                    "{\"email\":\"admin@doomed.co\",\"password\":\"a brand new passphrase\",\"displayName\":\"New Admin\"}"), 202);
        }
        assertThat(verifications.sentTo("admin@doomed.co")).hasSize(1);
        assertThat(verifications.notices()).isEmpty();
        Map<String, Object> fresh = jdbc.queryForMap("SELECT id, display_name, deleted_at FROM users WHERE email = 'admin@doomed.co'");
        assertThat(((Number) fresh.get("id")).longValue()).isNotEqualTo(admin.id());
        assertThat(fresh.get("display_name")).isEqualTo("New Admin");
        assertThat(fresh.get("deleted_at")).isNull();
        // The new account inherits nothing.
        long newId = ((Number) fresh.get("id")).longValue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE user_id = ?", Long.class, newId)).isZero();
    }
}
