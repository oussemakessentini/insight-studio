package com.oussamaksantini.insightstudio.chart;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Charts are not part of the public demo: with the demo enabled, anonymous visitors still get 401 on
 * every chart endpoint, also when naming the demo business. Same property as
 * {@code PublicDemoIntegrationTest}, so the two share one Spring context.
 */
@TestPropertySource(properties = "insight.demo.public=true")
class ChartPublicDemoIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void anonymousVisitorsOfTheDemoCannotUseCharts() throws Exception {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();
        long demo = db.business("Fieldstone Apparel Co.", "fieldstone-apparel", "USD", "UTC");
        db.store(demo, "BOS", "Demo Store", "Boston");
        // A business with members is never served as the demo, so the chart's author is not a member.
        long owner = new TestAccounts(jdbc).user("author@demo.test").id();
        long chart = jdbc.queryForObject("""
                INSERT INTO chart_definitions (business_id, title, created_by, updated_by) VALUES (?, 'Demo chart', ?, ?)
                RETURNING id
                """, Long.class, demo, owner, owner);

        String base = "/api/charts/" + chart;
        for (String path : List.of("/api/charts", "/api/charts/catalog", base, base + "/revisions", base + "/revisions/1",
                base + "/data")) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            mvc.perform(get(path).header(TestAccounts.BUSINESS_HEADER, demo)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/charts/preview").with(TestAccounts.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isUnauthorized());
        // The demo's ordinary reads still work, so the 401 is about charts.
        mvc.perform(get("/api/reports/monthly?from=2026-06-01&to=2026-06-30")).andExpect(status().isOk());
    }
}
