package com.oussamaksantini.insightstudio.accountdata;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.as;
import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The permission matrix of every endpoint of docs/account-management-contract.md: OWNER, ADMIN,
 * VIEWER, an unverified OWNER, a member of another business only (404), signed out (401), and the
 * public demo business (no members: 404 when signed in, 401 when not). Nothing may be changed by a
 * refused request.
 */
class AccountManagementPermissionsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    SqlFixture db;
    long business;
    long otherBusiness;
    long demoBusiness;
    Map<String, TestUser> actors;

    @BeforeEach
    void setUp() {
        db = new SqlFixture(jdbc);
        db.clear();
        TestAccounts accounts = new TestAccounts(jdbc);
        business = db.business("Matrix Co", "matrix-co", "EUR", "UTC");
        otherBusiness = db.business("Other Co", "other-co", "EUR", "UTC");
        demoBusiness = db.business("Fieldstone Apparel", "fieldstone-apparel", "USD", "America/New_York");
        actors = new LinkedHashMap<>();
        actors.put("OWNER", accounts.member("owner@matrix.co", business, Role.OWNER));
        actors.put("ADMIN", accounts.member("admin@matrix.co", business, Role.ADMIN));
        actors.put("VIEWER", accounts.member("viewer@matrix.co", business, Role.VIEWER));
        TestUser unverified = accounts.unverifiedUser("unverified@matrix.co");
        accounts.member(unverified, business, Role.OWNER);
        actors.put("UNVERIFIED_OWNER", unverified);
        actors.put("OTHER_BUSINESS_OWNER", accounts.member("owner@other.co", otherBusiness, Role.OWNER));
    }

    /** One endpoint and the status each actor gets. */
    record Case(HttpMethod method, String path, String body, Map<String, Integer> expected) {
    }

    private static Map<String, Integer> statuses(int owner, int admin, int viewer, int unverifiedOwner) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("OWNER", owner);
        expected.put("ADMIN", admin);
        expected.put("VIEWER", viewer);
        expected.put("UNVERIFIED_OWNER", unverifiedOwner);
        expected.put("OTHER_BUSINESS_OWNER", 404);
        expected.put("SIGNED_OUT", 401);
        return expected;
    }

    private List<Case> businessCases(long id) {
        String b = "/api/businesses/" + id;
        List<Case> cases = new ArrayList<>();
        cases.add(new Case(HttpMethod.GET, b + "/settings", null, statuses(200, 200, 200, 200)));
        // A currency change to the current value: allowed for the OWNER, changes nothing.
        cases.add(new Case(HttpMethod.PATCH, b, "{\"currency\":\"EUR\"}", statuses(200, 403, 403, 403)));
        cases.add(new Case(HttpMethod.GET, b + "/time-zone-preview?timeZone=Europe/Paris", null, statuses(200, 403, 403, 200)));
        cases.add(new Case(HttpMethod.GET, b + "/audit", null, statuses(200, 200, 403, 200)));
        cases.add(new Case(HttpMethod.GET, b + "/export", null, statuses(200, 403, 403, 403)));
        cases.add(new Case(HttpMethod.GET, b + "/deletion-preview", null, statuses(200, 403, 403, 403)));
        // A wrong confirmation: access was granted when the answer is 400; nothing is deleted.
        cases.add(new Case(HttpMethod.DELETE, b, "{\"password\":\"" + TestAccounts.PASSWORD + "\",\"confirmName\":\"nope\"}",
                statuses(400, 403, 403, 403)));
        return cases;
    }

    private int perform(Case c, String actor, long selected) throws Exception {
        MockHttpServletRequestBuilder request = request(c.method(), c.path());
        if (c.body() != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(c.body());
        }
        if (actor.equals("SIGNED_OUT")) {
            request.with(csrf());
        } else {
            request.with(as(actors.get(actor), selected));
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void everyBusinessEndpointFollowsTheMatrix() throws Exception {
        SoftAssertions softly = new SoftAssertions();
        for (Case c : businessCases(business)) {
            for (Map.Entry<String, Integer> expected : c.expected().entrySet()) {
                int status = perform(c, expected.getKey(), business);
                softly.assertThat(status).as("%s %s as %s", c.method(), c.path(), expected.getKey()).isEqualTo(expected.getValue());
            }
        }
        softly.assertAll();
        assertThat(db.count("businesses")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT currency FROM businesses WHERE id = ?", String.class, business)).isEqualTo("EUR");
    }

    @Test
    void thePublicDemoBusinessCanNeverBeReadChangedExportedOrDeleted() throws Exception {
        SoftAssertions softly = new SoftAssertions();
        for (Case c : businessCases(demoBusiness)) {
            for (String actor : List.of("OWNER", "OTHER_BUSINESS_OWNER")) {
                softly.assertThat(perform(c, actor, demoBusiness)).as("%s %s as %s", c.method(), c.path(), actor).isEqualTo(404);
            }
            softly.assertThat(perform(c, "SIGNED_OUT", demoBusiness)).as("%s %s signed out", c.method(), c.path()).isEqualTo(401);
        }
        softly.assertAll();
        assertThat(db.count("businesses")).isEqualTo(3);
    }

    @Test
    void accountEndpointsNeedASession() throws Exception {
        List<Case> cases = List.of(
                new Case(HttpMethod.GET, "/api/account/export", null, Map.of()),
                new Case(HttpMethod.GET, "/api/account/deletion-preview", null, Map.of()),
                new Case(HttpMethod.DELETE, "/api/account", "{\"password\":\"x\",\"confirmEmail\":\"nobody@example.com\"}", Map.of()));
        SoftAssertions softly = new SoftAssertions();
        for (Case c : cases) {
            softly.assertThat(perform(c, "SIGNED_OUT", business)).as("%s %s signed out", c.method(), c.path()).isEqualTo(401);
            for (String actor : List.of("OWNER", "VIEWER", "UNVERIFIED_OWNER", "OTHER_BUSINESS_OWNER")) {
                int expected = c.method() == HttpMethod.DELETE ? 400 : 200;
                softly.assertThat(perform(c, actor, business)).as("%s %s as %s", c.method(), c.path(), actor).isEqualTo(expected);
            }
        }
        softly.assertAll();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE deleted_at IS NOT NULL", Long.class)).isZero();
    }
}
