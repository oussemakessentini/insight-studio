package com.oussamaksantini.insightstudio.security;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.as;
import static org.assertj.core.api.Assertions.assertThat;
import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * With {@code insight.demo.public=true}, signed-out visitors read the demo business and nothing
 * else, and can change nothing. (Separate Spring context: the property is fixed at startup.)
 */
@TestPropertySource(properties = "insight.demo.public=true")
class PublicDemoIntegrationTest extends PostgresIntegrationTest {

    private static final String WINDOW = "from=2026-06-01&to=2026-06-30";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    SqlFixture db;
    long demo;
    long demoStore;
    long demoProduct;
    long demoSale;
    long privateBusiness;
    long privateStore;
    long privateProduct;
    long privateSale;
    TestUser privateOwner;

    @BeforeEach
    void loadFixture() {
        db = new SqlFixture(jdbc);
        db.clear();
        demo = db.business("Fieldstone Apparel Co.", "fieldstone-apparel", "USD", "UTC");
        demoStore = db.store(demo, "BOS", "Demo Store", "Boston");
        demoProduct = db.product(demo, "TEE", "Demo Tee", "Tops", "20.00");
        demoSale = db.sale(demoStore, "DEMO-1", "2026-06-01T10:00:00Z", demoProduct, 1, "20.00");

        privateBusiness = db.business("Private Co", "private-co", "EUR", "UTC");
        privateStore = db.store(privateBusiness, "BOS", "Private Store", null);
        privateProduct = db.product(privateBusiness, "TEE", "Private Tee", "Tops", "55.00");
        privateSale = db.sale(privateStore, "PRIVATE-1", "2026-06-01T10:00:00Z", privateProduct, 1, "55.00");
        privateOwner = new TestAccounts(jdbc).member("owner@private.test", privateBusiness, Role.OWNER);
    }

    private List<String> reads(long store, long product, long sale) {
        return List.of(
                "/api/dashboard/context", "/api/dashboard/summary?" + WINDOW, "/api/dashboard/revenue?" + WINDOW,
                "/api/dashboard/sales-by-store?" + WINDOW, "/api/dashboard/top-products?" + WINDOW,
                "/api/dashboard/recent-sales?" + WINDOW, "/api/products?" + WINDOW, "/api/products/categories",
                "/api/products/" + product + "?" + WINDOW, "/api/products/" + product + "/sales-trend?" + WINDOW,
                "/api/sales?" + WINDOW, "/api/sales/" + sale, "/api/stores?" + WINDOW, "/api/stores/" + store + "?" + WINDOW,
                "/api/stores/" + store + "/revenue?" + WINDOW, "/api/stores/" + store + "/top-products?" + WINDOW,
                "/api/reports/monthly?" + WINDOW, "/api/reports/categories?" + WINDOW,
                "/api/reports/monthly.csv?" + WINDOW, "/api/reports/categories.csv?" + WINDOW);
    }

    private String body(RequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as("%s -> %s", request, body).isEqualTo(expectedStatus);
        return body.toLowerCase(Locale.ROOT);
    }

    @Test
    void anonymousReadsTheDemoOnly() throws Exception {
        for (String path : reads(demoStore, demoProduct, demoSale)) {
            assertThat(body(get(path), 200)).as(path).doesNotContain("private");
        }
        mvc.perform(get("/api/dashboard/context"))
                .andExpect(jsonPath("$.business.slug").value("fieldstone-apparel"))
                .andExpect(jsonPath("$.access.role").value("DEMO"))
                .andExpect(jsonPath("$.access.canImport").value(false))
                .andExpect(jsonPath("$.access.canManageCatalog").value(false))
                .andExpect(jsonPath("$.access.canManageMembers").value(false))
                .andExpect(jsonPath("$.access.readOnly").value(true));
        mvc.perform(get("/api/session"))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.demo.enabled").value(true))
                .andExpect(jsonPath("$.demo.businessId").value(demo))
                .andExpect(jsonPath("$.demo.name").value("Fieldstone Apparel Co."));
    }

    @Test
    void anonymousNeverReachesAnotherBusiness() throws Exception {
        for (String path : reads(privateStore, privateProduct, privateSale)) {
            if (path.contains("/" + privateStore) || path.contains("/" + privateProduct) || path.contains("/" + privateSale)) {
                assertThat(body(get(path), 404)).as(path).doesNotContain("private");
            }
            assertThat(body(get(path).header(TestAccounts.BUSINESS_HEADER, privateBusiness), 401)).doesNotContain("private");
        }
        body(get("/api/dashboard/context").header(TestAccounts.BUSINESS_HEADER, demo), 200);
        body(get("/api/dashboard/context").header(TestAccounts.BUSINESS_HEADER, "nonsense"), 401);
        // Not demo-readable at all.
        body(get("/api/imports"), 401);
        body(get("/api/businesses"), 401);
        body(get("/api/businesses/" + demo + "/members"), 401);
    }

    @Test
    void everyWriteIsRefused() throws Exception {
        long stores = db.count("stores");
        long products = db.count("products");
        long sales = db.count("sales");
        long businesses = db.count("businesses");
        MockMultipartFile file = new MockMultipartFile("file", "x.csv", "text/csv",
                "store_code,receipt_number,sold_at,sku,quantity,unit_price\nBOS,N-1,2026-06-02T10:00:00Z,TEE,1,1.00\n"
                        .getBytes(StandardCharsets.UTF_8));
        // Each with a valid CSRF token: refused because nobody is signed in.
        List<RequestBuilder> writes = List.of(
                multipart("/api/imports").file(file).param("dryRun", "false").with(csrf()),
                post("/api/stores").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"N\",\"name\":\"N\"}")
                        .with(csrf()),
                post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"N\",\"name\":\"N\",\"category\":\"C\",\"listPrice\":1}")
                        .with(csrf()),
                post("/api/businesses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"N\",\"currency\":\"USD\",\"timeZone\":\"UTC\"}").with(csrf()),
                patch("/api/businesses/" + demo).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"N\"}")
                        .with(csrf()),
                post("/api/businesses/" + demo + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@private.test\",\"role\":\"OWNER\"}").with(csrf()),
                delete("/api/businesses/" + demo + "/members/" + privateOwner.id()).with(csrf()));
        for (RequestBuilder write : writes) {
            body(write, 401);
        }
        assertThat(db.count("stores")).isEqualTo(stores);
        assertThat(db.count("products")).isEqualTo(products);
        assertThat(db.count("sales")).isEqualTo(sales);
        assertThat(db.count("businesses")).isEqualTo(businesses);
        assertThat(db.count("memberships")).isEqualTo(1);
    }

    @Test
    void signedInUsersSeeTheirOwnBusinessNotTheDemo() throws Exception {
        String body = body(get("/api/sales?" + WINDOW).with(as(privateOwner)), 200);
        assertThat(body).contains("private-1").doesNotContain("demo-1");
        body(get("/api/dashboard/context").with(as(privateOwner)).header(TestAccounts.BUSINESS_HEADER, demo), 404);
        body(get("/api/sales/" + demoSale).with(as(privateOwner)), 404);
    }

    @Test
    void aBusinessWithMembersIsNeverServedAsTheDemo() throws Exception {
        new TestAccounts(jdbc).member("someone@demo.test", demo, Role.VIEWER);
        body(get("/api/dashboard/context"), 401);
        mvc.perform(get("/api/session")).andExpect(jsonPath("$.demo").doesNotExist());
    }
}
