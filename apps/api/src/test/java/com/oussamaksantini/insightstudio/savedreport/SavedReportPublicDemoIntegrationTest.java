package com.oussamaksantini.insightstudio.savedreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.PdfText;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With the public demo enabled, anonymous visitors download the demo's ad-hoc PDFs like its other
 * reports, but saved reports stay members-only (the demo has none). Same property as
 * {@code PublicDemoIntegrationTest}, so the two share one Spring context.
 */
@TestPropertySource(properties = "insight.demo.public=true")
class SavedReportPublicDemoIntegrationTest extends PostgresIntegrationTest {

    private static final String WINDOW = "from=2026-06-01&to=2026-06-30";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    long privateBusiness;
    long privateDefinition;

    @BeforeEach
    void loadFixture() throws Exception {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();
        long demo = db.business("Fieldstone Apparel Co.", "fieldstone-apparel", "USD", "UTC");
        long demoStore = db.store(demo, "BOS", "Demo Store", "Boston");
        long demoProduct = db.product(demo, "TEE", "Demo Tee", "Tops", "20.00");
        db.sale(demoStore, "DEMO-1", "2026-06-01T10:00:00Z", demoProduct, 1, "20.00");

        privateBusiness = db.business("Private Co", "private-co", "EUR", "UTC");
        db.store(privateBusiness, "P", "Private Store", null);
        TestUser owner = new TestAccounts(jdbc).member("owner@private.test", privateBusiness, Role.OWNER);
        privateDefinition = jdbc.queryForObject("""
                INSERT INTO saved_reports (business_id, name, kind, range_type, relative_preset, created_by)
                VALUES (?, 'Private plan', 'monthly', 'relative', 'last_30_days', ?) RETURNING id
                """, Long.class, privateBusiness, owner.id());
    }

    @Test
    void anonymousVisitorsDownloadTheDemosPdfs() throws Exception {
        byte[] monthly = mvc.perform(get("/api/reports/monthly.pdf?" + WINDOW))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"fieldstone-apparel-monthly-2026-06-01-to-2026-06-30.pdf\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(PdfText.text(monthly)).contains("Fieldstone Apparel Co.", "Total $20.00 1 1 $20.00")
                .doesNotContain("Private");
        byte[] categories = mvc.perform(get("/api/reports/categories.pdf?" + WINDOW))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(PdfText.text(categories)).contains("Category report", "Tops").doesNotContain("Private");

        mvc.perform(get("/api/reports/monthly.pdf?" + WINDOW).header(TestAccounts.BUSINESS_HEADER, privateBusiness))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void savedReportsAreNotPartOfThePublicDemo() throws Exception {
        String base = "/api/saved-reports/" + privateDefinition;
        for (String path : List.of("/api/saved-reports", base, base + "/report", base + "/report.csv", base + "/report.pdf")) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        String body = """
                {"name": "Demo", "kind": "monthly", "range": {"type": "relative", "preset": "last_7_days"}, "storeId": null}
                """;
        mvc.perform(post("/api/saved-reports").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(TestAccounts.csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(base).contentType(MediaType.APPLICATION_JSON).content(body).with(TestAccounts.csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(base).with(TestAccounts.csrf())).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_reports", Long.class)).isEqualTo(1);
    }
}
