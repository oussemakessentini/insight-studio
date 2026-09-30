package com.oussamaksantini.insightstudio.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * With no Cube URL (the default), analytics answers 503 and never calls Cube. The URL is blanked
 * explicitly so a developer's own INSIGHT_CUBE_URL (environment or infra/.env) cannot leak in.
 */
@TestPropertySource(properties = {"insight.cube.url=", "INSIGHT_CUBE_URL="})
class AnalyticsNotConfiguredIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();
        long business = db.business("Alpha Co", "alpha-co", "EUR", "Europe/Paris");
        mvc = TestAccounts.ownerMvc(context, jdbc, business);
    }

    @Test
    void answers503WhenCubeIsNotConfigured() throws Exception {
        mvc.perform(get("/api/analytics/summary").param("from", "2026-06-01").param("to", "2026-06-02"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Analytics is not configured."));
    }
}
