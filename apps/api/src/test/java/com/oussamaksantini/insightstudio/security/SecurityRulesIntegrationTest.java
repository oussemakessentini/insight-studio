package com.oussamaksantini.insightstudio.security;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Deny-by-default rules with the default configuration (public demo off). */
class SecurityRulesIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    long business;
    TestUser owner;

    @BeforeEach
    void loadFixture() {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();
        // Even the demo business is private while the public demo is off.
        business = db.business("Fieldstone Apparel Co.", "fieldstone-apparel", "USD", "UTC");
        owner = new TestAccounts(jdbc).user("owner@test.co");
    }

    @Test
    void businessDataNeedsASignInWhenTheDemoIsOff() throws Exception {
        for (String path : List.of("/api/dashboard/context", "/api/dashboard/summary", "/api/products", "/api/products/1",
                "/api/sales", "/api/sales/1", "/api/stores", "/api/stores/1", "/api/reports/monthly",
                "/api/reports/monthly.csv", "/api/analytics/summary", "/api/imports", "/api/imports/1", "/api/businesses",
                "/api/businesses/" + business + "/members", "/api/businesses/" + business + "/invitations")) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.detail").value("Sign in to continue."));
        }
        mvc.perform(get("/api/dashboard/context").header(TestAccounts.BUSINESS_HEADER, business))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownPathsAre401ForAnonymousAnd404WhenSignedIn() throws Exception {
        mvc.perform(get("/api/nope")).andExpect(status().isUnauthorized());
        mvc.perform(get("/")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/nope").with(as(owner))).andExpect(status().isNotFound());
    }

    @Test
    void publicEndpoints() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/session"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.memberships").isEmpty())
                .andExpect(jsonPath("$.demo").doesNotExist());
        // Issued again when the client already has one.
        mvc.perform(get("/api/session").cookie(new Cookie("XSRF-TOKEN", "abc")))
                .andExpect(cookie().value("XSRF-TOKEN", "abc"));
    }

    @Test
    void sessionShowsTheUserAndMemberships() throws Exception {
        new TestAccounts(jdbc).member(owner, business, Role.OWNER);
        mvc.perform(get("/api/session").with(as(owner)))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.id").value(owner.id()))
                .andExpect(jsonPath("$.user.email").value("owner@test.co"))
                .andExpect(jsonPath("$.memberships[0].businessId").value(business))
                .andExpect(jsonPath("$.memberships[0].slug").value("fieldstone-apparel"))
                .andExpect(jsonPath("$.memberships[0].role").value("OWNER"));
    }

    @Test
    void writesNeedTheCsrfHeader() throws Exception {
        new TestAccounts(jdbc).member(owner, business, Role.OWNER);
        String store = "{\"code\":\"S\",\"name\":\"S\"}";
        mvc.perform(post("/api/stores").with(as(owner)).contentType(MediaType.APPLICATION_JSON).content(store))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("CSRF")));
        // A form parameter is not accepted in place of the header.
        mvc.perform(post("/api/stores").with(as(owner)).cookie(new Cookie("XSRF-TOKEN", "t")).param("_csrf", "t")
                        .contentType(MediaType.APPLICATION_JSON).content(store))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/stores").with(as(owner)).with(TestAccounts.invalidCsrf())
                        .contentType(MediaType.APPLICATION_JSON).content(store))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stores", Long.class)).isZero();
        mvc.perform(post("/api/stores").with(as(owner, business)).contentType(MediaType.APPLICATION_JSON).content(store))
                .andExpect(status().isCreated());
        // Public POSTs need it too.
        mvc.perform(post("/api/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@test.co\",\"password\":\"" + TestAccounts.PASSWORD + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void configurationKeepsTheDemoOffAndImportsUnswitched() throws IOException {
        Properties base = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        Properties demo = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-demo.properties"));
        assertThat(base.getProperty("insight.demo.public")).isEqualTo("false");
        assertThat(demo.getProperty("insight.demo.public")).isEqualTo("true");
        assertThat(base).doesNotContainKeys("insight.imports.enabled", "insight.dashboard.business-slug");
        assertThat(demo).doesNotContainKeys("insight.imports.enabled", "insight.dashboard.business-slug");
        assertThat(new ClassPathResource("application-local.properties").exists()).isFalse();
    }
}
