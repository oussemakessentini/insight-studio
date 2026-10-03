package com.oussamaksantini.insightstudio.customdashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The public demo has no custom dashboards: with the demo enabled, anonymous visitors get 401 on every
 * custom dashboard endpoint, also when naming the demo business, while the overview dashboard
 * ({@code /api/dashboard/**}) stays readable. Same property as {@code PublicDemoIntegrationTest}, so
 * they share one Spring context.
 */
@TestPropertySource(properties = "insight.demo.public=true")
class CustomDashboardPublicDemoIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void anonymousVisitorsOfTheDemoCannotUseCustomDashboards() throws Exception {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();
        long demo = db.business("Fieldstone Apparel Co.", "fieldstone-apparel", "USD", "UTC");
        db.store(demo, "BOS", "Demo Store", "Boston");
        // A business with members is never served as the demo, so the author is not a member.
        long author = new TestAccounts(jdbc).user("author@demo.test").id();
        long chart = jdbc.queryForObject("""
                INSERT INTO chart_definitions (business_id, title, created_by, updated_by) VALUES (?, 'Demo chart', ?, ?)
                RETURNING id
                """, Long.class, demo, author, author);
        long dashboard = jdbc.queryForObject("""
                INSERT INTO dashboards (business_id, name, created_by, updated_by) VALUES (?, 'Demo board', ?, ?)
                RETURNING id
                """, Long.class, demo, author, author);

        String base = "/api/dashboards/" + dashboard;
        for (String path : List.of("/api/dashboards", base, base + "?revision=1", base + "/revisions",
                "/api/charts/" + chart + "/dashboards")) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            mvc.perform(get(path).header(TestAccounts.BUSINESS_HEADER, demo)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/dashboards").with(TestAccounts.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Mine\"}")).andExpect(status().isUnauthorized());
        mvc.perform(put(base).with(TestAccounts.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post(base + "/duplicate").with(TestAccounts.csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete(base).with(TestAccounts.csrf())).andExpect(status().isUnauthorized());
        // The overview dashboard of the demo still works, so the 401 is about custom dashboards.
        mvc.perform(get("/api/dashboard/summary")).andExpect(status().isOk());
    }
}
