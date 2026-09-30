package com.oussamaksantini.insightstudio.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.common.ImportsProperties;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With the default configuration (imports disabled) the import endpoints don't exist at all: they
 * answer 404 like any unknown path, and no import bean is created.
 */
class ImportsDisabledIntegrationTest extends PostgresIntegrationTest {

    private static final String CSV = """
            store_code,receipt_number,sold_at,sku,quantity,unit_price
            A,R-1,2026-09-01T10:00:00Z,TEE-1,1,1.00
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ImportsProperties imports;

    @Test
    void importsAreDisabledByDefault() {
        assertThat(imports.enabled()).isFalse();
    }

    @Test
    void noImportBeanExists() {
        String importPackage = ImportService.class.getPackageName();
        assertThat(context.getBeanNamesForType(ImportController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ImportService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ImportQueries.class)).isEmpty();
        assertThat(Arrays.stream(context.getBeanDefinitionNames())
                .map(context::getType)
                .filter(type -> type != null && type.getPackageName().startsWith(importPackage)))
                .isEmpty();
    }

    @Test
    void endpointsAnswer404AndWriteNothing() throws Exception {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();
        long business = db.business("Test Co", "test-co", "USD", "UTC");
        db.store(business, "A", "Alpha", null);
        db.product(business, "TEE-1", "Tee", "Tops", "1.00");

        MockMultipartFile file = new MockMultipartFile("file", "sales.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/imports").file(file).param("dryRun", "false"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mvc.perform(multipart("/api/imports").file(file)).andExpect(status().isNotFound());
        mvc.perform(get("/api/imports")).andExpect(status().isNotFound());
        mvc.perform(get("/api/imports/1")).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_batches", Long.class)).isZero();
    }
}
