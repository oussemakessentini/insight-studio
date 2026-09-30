package com.oussamaksantini.insightstudio.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.security.RateLimit;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.CapturingInvitationNotifier;
import com.oussamaksantini.insightstudio.testsupport.CapturingPasswordResetNotifier;
import com.oussamaksantini.insightstudio.testsupport.CapturingVerificationNotifier;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Email verification over real HTTP: links are single-use, expire and verify only their own
 * account; unverified accounts can read but not create or change businesses; an invitation or a
 * reset link sent to the address verifies it too.
 */
class EmailVerificationIntegrationTest extends PostgresIntegrationTest {

    private static final String INVALID = "This verification link is invalid or has expired.";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CapturingVerificationNotifier verificationMail;

    @Autowired
    CapturingInvitationNotifier invitationMail;

    @Autowired
    CapturingPasswordResetNotifier resetMail;

    SqlFixture db;
    TestAccounts accounts;

    @BeforeEach
    void clean() {
        db = new SqlFixture(jdbc);
        db.clear();
        accounts = new TestAccounts(jdbc);
        verificationMail.clear();
        invitationMail.clear();
        resetMail.clear();
    }

    private HttpApiClient browser() throws Exception {
        HttpApiClient client = new HttpApiClient(port);
        client.get("/api/session");
        return client;
    }

    private HttpApiClient signedIn(String email) throws Exception {
        HttpApiClient client = browser();
        expect(client.postJson("/api/auth/sign-in",
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, TestAccounts.PASSWORD)), 200);
        return client;
    }

    /** Signs up through the API; returns the emailed verification token. */
    private String signUp(String email) throws Exception {
        try (HttpApiClient client = browser()) {
            HttpResponse<String> response = client.postJson("/api/auth/sign-up",
                    "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"New\"}".formatted(email, TestAccounts.PASSWORD));
            assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        }
        return verificationMail.sentTo(email).getLast().token();
    }

    private static HttpResponse<String> verify(HttpApiClient client, String token) throws Exception {
        return client.postJson("/api/auth/verify-email", "{\"token\":\"%s\"}".formatted(token));
    }

    private static void expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("%s %s -> %s", response.request().method(), response.request().uri(),
                response.body()).isEqualTo(status);
    }

    private boolean verified(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT email_verified_at IS NOT NULL FROM users WHERE lower(email) = lower(?)", Boolean.class, email));
    }

    private static HttpResponse<String> createBusiness(HttpApiClient client) throws Exception {
        return client.postJson("/api/businesses", "{\"name\":\"Shop\",\"currency\":\"EUR\",\"timeZone\":\"UTC\"}");
    }

    @Test
    void signUpSendsASingleUseLinkThatVerifiesTheAccount() throws Exception {
        String token = signUp("new@example.com");
        assertThat(verified("new@example.com")).isFalse();
        CapturingVerificationNotifier.Sent sent = verificationMail.last();
        assertThat(sent.link()).isEqualTo("http://localhost:5173/verify-email?token=" + token);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(sent.expiresAt()).isBetween(Instant.now().plus(Duration.ofHours(24)).minusSeconds(60),
                Instant.now().plus(Duration.ofHours(24)));
        assertThat(jdbc.queryForObject("SELECT token_sha256 FROM email_verification_tokens", String.class))
                .isEqualTo(AccountService.sha256(token)).isNotEqualTo(token);

        try (HttpApiClient anyone = browser()) {
            // The link works in any browser, signed out; it signs no one in.
            expect(verify(anyone, token), 204);
            assertThat((Boolean) JsonPath.read(anyone.get("/api/session").body(), "$.authenticated")).isFalse();
            HttpResponse<String> again = verify(anyone, token);
            expect(again, 400);
            assertThat((String) JsonPath.read(again.body(), "$.detail")).isEqualTo(INVALID);
        }
        assertThat(verified("new@example.com")).isTrue();
        try (HttpApiClient owner = signedIn("new@example.com")) {
            assertThat((Boolean) JsonPath.read(owner.get("/api/session").body(), "$.user.emailVerified")).isTrue();
            expect(createBusiness(owner), 201);
        }
    }

    @Test
    void expiredUnknownAndSupersededLinksAreRefused() throws Exception {
        String first = signUp("new@example.com");
        try (HttpApiClient client = signedIn("new@example.com")) {
            expect(client.postJson("/api/auth/verify-email/resend", "{}"), 202);
            String second = verificationMail.last().token();
            assertThat(second).isNotEqualTo(first);
            // A new link replaces the old one.
            expect(verify(client, first), 400);

            jdbc.update("UPDATE email_verification_tokens SET expires_at = now() - interval '1 minute' WHERE used_at IS NULL");
            expect(verify(client, second), 400);
            expect(verify(client, "made-up"), 400);
            expect(verify(client, ""), 400);
        }
        assertThat(verified("new@example.com")).isFalse();
    }

    @Test
    void anUnverifiedAccountReadsButCannotCreateOrChangeABusiness() throws Exception {
        TestUser user = accounts.unverifiedUser("unverified@example.com");
        long business = db.business("Existing Co", "existing-co", "EUR", "UTC");
        db.store(business, "S1", "Store", null);
        accounts.member(user, business, Role.OWNER);

        try (HttpApiClient client = signedIn(user.email())) {
            HttpResponse<String> created = createBusiness(client);
            expect(created, 403);
            assertThat((String) JsonPath.read(created.body(), "$.detail")).startsWith("Verify your email address first");

            HttpResponse<String> context = client.get("/api/dashboard/context");
            expect(context, 200);
            assertThat((Boolean) JsonPath.read(context.body(), "$.access.emailVerified")).isFalse();
            assertThat((Boolean) JsonPath.read(context.body(), "$.access.readOnly")).isTrue();
            assertThat((Boolean) JsonPath.read(context.body(), "$.access.canImport")).isFalse();
            expect(client.get("/api/dashboard/summary"), 200);
            expect(client.get("/api/stores"), 200);

            expect(client.postJson("/api/stores", "{\"code\":\"S2\",\"name\":\"Second\"}"), 403);
            expect(client.postJson("/api/products", "{\"sku\":\"P1\",\"name\":\"P\",\"category\":\"C\",\"listPrice\":1}"), 403);
            String boundary = "----unverified";
            expect(client.postMultipart("/api/imports?dryRun=false", boundary, HttpApiClient.multipartFile(boundary, "s.csv",
                    "store_code,receipt_number,sold_at,sku,quantity,unit_price\n".getBytes(StandardCharsets.UTF_8))), 403);
            expect(client.postJson("/api/businesses/%d/invitations".formatted(business),
                    "{\"email\":\"x@example.com\",\"role\":\"VIEWER\"}"), 403);
            expect(client.patchJson("/api/businesses/" + business, "{\"name\":\"Renamed\"}"), 403);
        }
        assertThat(db.count("stores")).isEqualTo(1);
        assertThat(db.count("products")).isZero();
        assertThat(db.count("invitations")).isZero();
        assertThat(db.count("businesses")).isEqualTo(1);
    }

    @Test
    void aLinkVerifiesOnlyItsOwnAccountWhoeverOpensIt() throws Exception {
        String aliceToken = signUp("alice@example.com");
        signUp("bob@example.com");
        try (HttpApiClient bob = signedIn("bob@example.com")) {
            // Bob opens Alice's link while signed in as himself.
            expect(verify(bob, aliceToken), 204);
            assertThat(verified("alice@example.com")).isTrue();
            assertThat(verified("bob@example.com")).isFalse();
            assertThat((Boolean) JsonPath.read(bob.get("/api/session").body(), "$.user.emailVerified")).isFalse();
            expect(createBusiness(bob), 403);
            assertThat((String) JsonPath.read(bob.get("/api/session").body(), "$.user.email")).isEqualTo("bob@example.com");
        }
    }

    @Test
    void resendIsLimitedAndDoesNothingForVerifiedAccounts() throws Exception {
        signUp("new@example.com");
        try (HttpApiClient client = signedIn("new@example.com")) {
            for (int i = 0; i < RateLimit.VERIFICATION_EMAILS_PER_ACCOUNT.max(); i++) {
                expect(client.postJson("/api/auth/verify-email/resend", "{}"), 202);
            }
            HttpResponse<String> limited = client.postJson("/api/auth/verify-email/resend", "{}");
            expect(limited, 429);
            assertThat(limited.headers().firstValue("Retry-After")).isPresent();
            assertThat(verificationMail.sentTo("new@example.com")).hasSize(1 + RateLimit.VERIFICATION_EMAILS_PER_ACCOUNT.max());
        }
        accounts.user("verified@example.com");
        try (HttpApiClient client = signedIn("verified@example.com"); HttpApiClient nobody = browser()) {
            expect(client.postJson("/api/auth/verify-email/resend", "{}"), 202);
            assertThat(verificationMail.sentTo("verified@example.com")).isEmpty();
            expect(nobody.postJson("/api/auth/verify-email/resend", "{}"), 401);
        }
    }

    @Test
    void anInvitationToTheAddressVerifiesIt() throws Exception {
        long business = db.business("Invite Co", "invite-co", "EUR", "UTC");
        TestUser owner = accounts.member("owner@example.com", business, Role.OWNER);
        TestUser invitee = accounts.unverifiedUser("invitee@example.com");
        TestUser other = accounts.unverifiedUser("other@example.com");
        try (HttpApiClient ownerClient = signedIn(owner.email())) {
            expect(ownerClient.postJson("/api/businesses/%d/invitations".formatted(business),
                    "{\"email\":\"invitee@example.com\",\"role\":\"ADMIN\"}"), 201);
        }
        String token = invitationMail.last().token();

        // Another unverified account cannot use it, and is not verified by trying.
        try (HttpApiClient client = signedIn(other.email())) {
            expect(client.postJson("/api/invitations/accept", "{\"token\":\"%s\"}".formatted(token)), 403);
        }
        assertThat(verified(other.email())).isFalse();

        try (HttpApiClient client = signedIn(invitee.email())) {
            expect(client.postJson("/api/invitations/accept", "{\"token\":\"%s\"}".formatted(token)), 200);
            assertThat(verified(invitee.email())).isTrue();
            // Verified by the invitation: the new admin can work in the business.
            expect(client.postJson("/api/stores", "{\"code\":\"S1\",\"name\":\"First\"}"), 201);
        }
    }

    @Test
    void aPasswordResetByEmailVerifiesTheAddress() throws Exception {
        accounts.unverifiedUser("reset@example.com");
        try (HttpApiClient client = browser()) {
            expect(client.postJson("/api/auth/password/forgot", "{\"email\":\"reset@example.com\"}"), 202);
            expect(client.postJson("/api/auth/password/reset", "{\"token\":\"%s\",\"newPassword\":\"a brand new passphrase\"}"
                    .formatted(resetMail.last().orElseThrow().token())), 204);
        }
        assertThat(verified("reset@example.com")).isTrue();
    }

    @Test
    void tokenGuessingIsLimitedPerIp() throws Exception {
        String token = signUp("new@example.com");
        try (HttpApiClient client = browser()) {
            for (int i = 0; i < RateLimit.VERIFICATION_TOKEN_PER_IP.max(); i++) {
                expect(verify(client, "guess-" + i), 400);
            }
            expect(verify(client, token), 429);
        }
        assertThat(verified("new@example.com")).isFalse();
    }
}
