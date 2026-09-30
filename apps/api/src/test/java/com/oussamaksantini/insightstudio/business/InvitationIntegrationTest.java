package com.oussamaksantini.insightstudio.business;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.security.RateLimit;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.CapturingInvitationNotifier;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Invitations over real HTTP sessions (each {@link HttpApiClient} is a browser): who may invite,
 * that inviting never reveals whether an account exists, and that a link is single-use, expires,
 * works only for the invited address and only for its own business.
 */
class InvitationIntegrationTest extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CapturingInvitationNotifier notifier;

    SqlFixture db;
    TestAccounts accounts;
    long a;
    long b;
    TestUser aOwner;
    TestUser aAdmin;
    TestUser aViewer;
    TestUser bOwner;
    TestUser invitee;

    @BeforeEach
    void setUp() {
        db = new SqlFixture(jdbc);
        db.clear();
        notifier.clear();
        accounts = new TestAccounts(jdbc);
        a = db.business("Alpha Co", "alpha-co", "EUR", "UTC");
        b = db.business("Bravo Co", "bravo-co", "USD", "UTC");
        aOwner = accounts.member("owner@alpha.test", a, Role.OWNER);
        aAdmin = accounts.member("admin@alpha.test", a, Role.ADMIN);
        aViewer = accounts.member("viewer@alpha.test", a, Role.VIEWER);
        bOwner = accounts.member("owner@bravo.test", b, Role.OWNER);
        invitee = accounts.user("invitee@example.com");
    }

    private HttpApiClient signedIn(TestUser user) throws Exception {
        HttpApiClient client = anonymous();
        expect(client.postJson("/api/auth/sign-in",
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(user.email(), TestAccounts.PASSWORD)), 200);
        return client;
    }

    private HttpApiClient anonymous() throws Exception {
        HttpApiClient client = new HttpApiClient(port);
        client.get("/api/session");
        return client;
    }

    private static HttpResponse<String> invite(HttpApiClient client, long business, String email, String role) throws Exception {
        return client.postJson("/api/businesses/%d/invitations".formatted(business),
                "{\"email\":\"%s\",\"role\":\"%s\"}".formatted(email, role));
    }

    private static HttpResponse<String> preview(HttpApiClient client, String token) throws Exception {
        return client.postJson("/api/invitations/preview", "{\"token\":\"%s\"}".formatted(token));
    }

    private static HttpResponse<String> accept(HttpApiClient client, String token) throws Exception {
        return client.postJson("/api/invitations/accept", "{\"token\":\"%s\"}".formatted(token));
    }

    private static void expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("%s %s -> %s", response.request().method(), response.request().uri(),
                response.body()).isEqualTo(status);
    }

    private static String detail(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.detail");
    }

    private Role roleIn(TestUser user, long business) {
        return jdbc.queryForList("SELECT role FROM memberships WHERE user_id = ? AND business_id = ?", String.class,
                user.id(), business).stream().findFirst().map(Role::valueOf).orElse(null);
    }

    /** A's owner invites {@code email}; returns the emailed token. */
    private String invited(String email, String role) throws Exception {
        try (HttpApiClient owner = signedIn(aOwner)) {
            expect(invite(owner, a, email, role), 201);
        }
        return notifier.last().token();
    }

    @Nested
    class WhoMayInvite {

        @Test
        void ownersInviteAnyRoleAdminsViewersAndAdminsOnly() throws Exception {
            try (HttpApiClient owner = signedIn(aOwner); HttpApiClient admin = signedIn(aAdmin);
                    HttpApiClient viewer = signedIn(aViewer)) {
                expect(invite(owner, a, "new-owner@example.com", "OWNER"), 201);
                expect(invite(admin, a, "new-admin@example.com", "ADMIN"), 201);
                expect(invite(admin, a, "new-viewer@example.com", "viewer"), 201);
                HttpResponse<String> adminOwner = invite(admin, a, "x@example.com", "OWNER");
                expect(adminOwner, 403);
                assertThat(detail(adminOwner)).isEqualTo("You need the OWNER role for this.");
                expect(invite(viewer, a, "x@example.com", "VIEWER"), 403);
                expect(viewer.get("/api/businesses/%d/invitations".formatted(a)), 403);

                HttpResponse<String> list = admin.get("/api/businesses/%d/invitations".formatted(a));
                expect(list, 200);
                assertThat((List<String>) JsonPath.read(list.body(), "$[*].email"))
                        .containsExactly("new-viewer@example.com", "new-admin@example.com", "new-owner@example.com");
                assertThat((String) JsonPath.read(list.body(), "$[2].invitedBy")).isEqualTo("owner");
            }
            assertThat(notifier.sent()).hasSize(3);
            assertThat(notifier.sent()).extracting(CapturingInvitationNotifier.Sent::businessName).containsOnly("Alpha Co");
        }

        @Test
        void anotherBusinessOrNoSessionCannotTouchInvitations() throws Exception {
            try (HttpApiClient bravo = signedIn(bOwner); HttpApiClient nobody = anonymous()) {
                expect(invite(bravo, a, "x@example.com", "VIEWER"), 404);
                expect(bravo.get("/api/businesses/%d/invitations".formatted(a)), 404);
                // The X-Business-Id header cannot select A either: the path decides, membership is checked.
                expect(bravo.get("/api/businesses/%d/invitations".formatted(a), "X-Business-Id", Long.toString(a)), 404);
                expect(invite(nobody, a, "x@example.com", "VIEWER"), 401);
                expect(nobody.get("/api/businesses/%d/invitations".formatted(a)), 401);
                expect(nobody.postJsonWithoutCsrf("/api/businesses/%d/invitations".formatted(a), "{}"), 403);
            }
            try (HttpApiClient owner = signedIn(aOwner)) {
                expect(owner.postJsonWithoutCsrf("/api/businesses/%d/invitations".formatted(a),
                        "{\"email\":\"x@example.com\",\"role\":\"VIEWER\"}"), 403);
            }
            assertThat(db.count("invitations")).isZero();
            assertThat(notifier.sent()).isEmpty();
        }

        @Test
        void revokingFollowsTheSameRules() throws Exception {
            try (HttpApiClient owner = signedIn(aOwner); HttpApiClient admin = signedIn(aAdmin);
                    HttpApiClient bravo = signedIn(bOwner)) {
                long ownerInvite = ((Number) JsonPath.read(invite(owner, a, "o@example.com", "OWNER").body(), "$.id")).longValue();
                String ownerToken = notifier.last().token();
                long viewerInvite = ((Number) JsonPath.read(invite(admin, a, "v@example.com", "VIEWER").body(), "$.id")).longValue();
                String viewerToken = notifier.last().token();

                expect(admin.delete("/api/businesses/%d/invitations/%d".formatted(a, ownerInvite)), 403);
                expect(bravo.delete("/api/businesses/%d/invitations/%d".formatted(a, viewerInvite)), 404);
                expect(bravo.delete("/api/businesses/%d/invitations/%d".formatted(b, viewerInvite)), 404);
                expect(admin.delete("/api/businesses/%d/invitations/%d".formatted(a, viewerInvite)), 204);
                expect(admin.delete("/api/businesses/%d/invitations/%d".formatted(a, viewerInvite)), 404);
                expect(owner.delete("/api/businesses/%d/invitations/%d".formatted(a, ownerInvite)), 204);

                try (HttpApiClient anyone = anonymous()) {
                    expect(preview(anyone, ownerToken), 400);
                    expect(preview(anyone, viewerToken), 400);
                }
            }
        }

        @Test
        void directMemberAdditionIsGone() throws Exception {
            try (HttpApiClient owner = signedIn(aOwner)) {
                expect(owner.postJson("/api/businesses/%d/members".formatted(a),
                        "{\"email\":\"%s\",\"role\":\"VIEWER\"}".formatted(invitee.email())), 405);
            }
            assertThat(roleIn(invitee, a)).isNull();
        }
    }

    @Nested
    class NoAccountDisclosure {

        @Test
        void invitingAnAccountOrAnUnknownAddressLooksTheSame() throws Exception {
            HttpResponse<String> existing;
            HttpResponse<String> unknown;
            try (HttpApiClient owner = signedIn(aOwner)) {
                existing = invite(owner, a, invitee.email(), "VIEWER");
                unknown = invite(owner, a, "nobody-here@example.com", "VIEWER");
            }
            expect(existing, 201);
            expect(unknown, 201);
            assertThat(normalized(existing.body(), invitee.email())).isEqualTo(normalized(unknown.body(), "nobody-here@example.com"));
            // Both addresses get the same email.
            assertThat(notifier.sent()).extracting(CapturingInvitationNotifier.Sent::email)
                    .containsExactly(invitee.email(), "nobody-here@example.com");
            // An invitation creates no membership, whether or not the account exists.
            assertThat(roleIn(invitee, a)).isNull();
        }

        private String normalized(String body, String email) {
            return body.replace(email, "EMAIL")
                    .replaceAll("\"id\":\\d+", "\"id\":0")
                    .replaceAll("\"(createdAt|expiresAt)\":\"[^\"]+\"", "\"$1\":\"T\"");
        }
    }

    @Nested
    class Accepting {

        @Test
        void theInvitedAccountJoinsWithTheRoleAndTheLinkIsSpent() throws Exception {
            String token = invited(invitee.email().toUpperCase(), "ADMIN");
            CapturingInvitationNotifier.Sent sent = notifier.last();
            assertThat(sent.link()).startsWith("http://localhost:5173/invite?token=");
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            assertThat(sent.expiresAt()).isBetween(Instant.now().plus(Duration.ofDays(7)).minusSeconds(60),
                    Instant.now().plus(Duration.ofDays(7)));
            // Only the hash is stored.
            assertThat(jdbc.queryForObject("SELECT token_sha256 FROM invitations", String.class))
                    .isEqualTo(InvitationService.sha256(token)).isNotEqualTo(token);

            try (HttpApiClient anyone = anonymous()) {
                HttpResponse<String> shown = preview(anyone, token);
                expect(shown, 200);
                assertThat((String) JsonPath.read(shown.body(), "$.businessName")).isEqualTo("Alpha Co");
                assertThat((String) JsonPath.read(shown.body(), "$.role")).isEqualTo("ADMIN");
                assertThat((String) JsonPath.read(shown.body(), "$.invitedBy")).isEqualTo("owner");
                assertThat((String) JsonPath.read(shown.body(), "$.email")).isEqualTo("INVITEE@EXAMPLE.COM");
                // Previewing needs no account, accepting does.
                expect(accept(anyone, token), 401);
            }

            try (HttpApiClient browser = signedIn(invitee)) {
                expect(browser.get("/api/dashboard/context", "X-Business-Id", Long.toString(a)), 404);
                HttpResponse<String> joined = accept(browser, token);
                expect(joined, 200);
                assertThat(((Number) JsonPath.read(joined.body(), "$.businessId")).longValue()).isEqualTo(a);
                assertThat((String) JsonPath.read(joined.body(), "$.role")).isEqualTo("ADMIN");
                assertThat(roleIn(invitee, a)).isEqualTo(Role.ADMIN);
                // The same session now sees A, and only A.
                expect(browser.get("/api/dashboard/context", "X-Business-Id", Long.toString(a)), 200);
                expect(browser.get("/api/dashboard/context", "X-Business-Id", Long.toString(b)), 404);

                // Single use.
                HttpResponse<String> again = accept(browser, token);
                expect(again, 400);
                assertThat(detail(again)).isEqualTo("This invitation is invalid or has expired.");
            }
            try (HttpApiClient anyone = anonymous()) {
                expect(preview(anyone, token), 400);
            }
            try (HttpApiClient owner = signedIn(aOwner)) {
                HttpResponse<String> open = owner.get("/api/businesses/%d/invitations".formatted(a));
                assertThat((List<?>) JsonPath.read(open.body(), "$")).isEmpty();
            }
        }

        @Test
        void onlyTheInvitedAddressCanAccept() throws Exception {
            String token = invited(invitee.email(), "OWNER");
            try (HttpApiClient intruder = signedIn(bOwner)) {
                HttpResponse<String> refused = accept(intruder, token);
                expect(refused, 403);
                assertThat(detail(refused)).contains("different email address");
            }
            assertThat(roleIn(bOwner, a)).isNull();
            // The refusal did not spend the link.
            try (HttpApiClient browser = signedIn(invitee)) {
                expect(accept(browser, token), 200);
            }
            assertThat(roleIn(invitee, a)).isEqualTo(Role.OWNER);
        }

        @Test
        void aNewAccountCanSignUpWithTheInvitedAddressAndAccept() throws Exception {
            String token = invited("brand-new@example.com", "VIEWER");
            try (HttpApiClient browser = anonymous()) {
                expect(preview(browser, token), 200);
                expect(browser.postJson("/api/auth/sign-up",
                        "{\"email\":\"brand-new@example.com\",\"password\":\"%s\",\"displayName\":\"New\"}"
                                .formatted(TestAccounts.PASSWORD)), 201);
                HttpResponse<String> joined = accept(browser, token);
                expect(joined, 200);
                assertThat((String) JsonPath.read(joined.body(), "$.name")).isEqualTo("Alpha Co");
                HttpResponse<String> session = browser.get("/api/session");
                assertThat((List<String>) JsonPath.read(session.body(), "$.memberships[*].role")).containsExactly("VIEWER");
            }
        }

        @Test
        void expiredRevokedReplacedAndUnknownLinksAreRefusedAlike() throws Exception {
            String expired = invited("expired@example.com", "VIEWER");
            jdbc.update("UPDATE invitations SET expires_at = now() - interval '1 minute' WHERE lower(email) = 'expired@example.com'");
            String replaced = invited(invitee.email(), "VIEWER");
            String current = invited(invitee.email(), "ADMIN");

            try (HttpApiClient browser = signedIn(invitee)) {
                for (String token : List.of(expired, replaced, "made-up-token", "")) {
                    HttpResponse<String> response = accept(browser, token);
                    expect(response, 400);
                    assertThat(detail(response)).isEqualTo("This invitation is invalid or has expired.");
                    expect(preview(browser, token), 400);
                }
                expect(accept(browser, current), 200);
            }
            assertThat(roleIn(invitee, a)).isEqualTo(Role.ADMIN);
            // An expired invitation is not listed; re-inviting replaces it.
            try (HttpApiClient owner = signedIn(aOwner)) {
                HttpResponse<String> open = owner.get("/api/businesses/%d/invitations".formatted(a));
                assertThat((List<?>) JsonPath.read(open.body(), "$")).isEmpty();
                expect(invite(owner, a, "expired@example.com", "VIEWER"), 201);
            }
        }

        @Test
        void aRemovedOrDemotedInviterLeavesLinksThatNoLongerWork() throws Exception {
            String byAdmin;
            try (HttpApiClient admin = signedIn(aAdmin)) {
                expect(invite(admin, a, invitee.email(), "ADMIN"), 201);
                byAdmin = notifier.last().token();
            }
            jdbc.update("UPDATE memberships SET role = 'VIEWER' WHERE user_id = ? AND business_id = ?", aAdmin.id(), a);
            try (HttpApiClient browser = signedIn(invitee)) {
                expect(preview(browser, byAdmin), 400);
                expect(accept(browser, byAdmin), 400);
            }
            assertThat(roleIn(invitee, a)).isNull();
        }

        @Test
        void aMemberCannotAcceptTwiceAndATokenOnlyOpensItsOwnBusiness() throws Exception {
            String token = invited(bOwner.email(), "VIEWER");
            try (HttpApiClient bravo = signedIn(bOwner)) {
                expect(accept(bravo, token), 200);
                // B's owner is now A's viewer and still B's owner; nothing about B changed.
                assertThat(roleIn(bOwner, a)).isEqualTo(Role.VIEWER);
                assertThat(roleIn(bOwner, b)).isEqualTo(Role.OWNER);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE business_id = ?", Long.class, b)).isEqualTo(1);
            }
            try (HttpApiClient owner = signedIn(aOwner)) {
                HttpResponse<String> again = invite(owner, a, bOwner.email(), "ADMIN");
                expect(again, 409);
            }
        }
    }

    @Nested
    class RateLimits {

        @Test
        void sendingIsLimitedPerAccount() throws Exception {
            try (HttpApiClient admin = signedIn(aAdmin); HttpApiClient owner = signedIn(aOwner)) {
                for (int i = 0; i < RateLimit.INVITATIONS_PER_ACCOUNT.max(); i++) {
                    expect(invite(admin, a, "person" + i + "@example.com", "VIEWER"), 201);
                }
                HttpResponse<String> limited = invite(admin, a, "one-more@example.com", "VIEWER");
                expect(limited, 429);
                assertThat(limited.headers().firstValue("Retry-After")).isPresent();
                // Another inviter is not affected.
                expect(invite(owner, a, "one-more@example.com", "VIEWER"), 201);
            }
        }

        @Test
        void tokenGuessingIsLimitedPerIp() throws Exception {
            String token = invited(invitee.email(), "VIEWER");
            try (HttpApiClient browser = anonymous()) {
                for (int i = 0; i < RateLimit.INVITATION_TOKEN_PER_IP.max(); i++) {
                    expect(preview(browser, "guess-" + i), 400);
                }
                expect(preview(browser, token), 429);
            }
        }
    }
}
