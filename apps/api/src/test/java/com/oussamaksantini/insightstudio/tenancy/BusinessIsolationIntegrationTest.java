package com.oussamaksantini.insightstudio.tenancy;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Two businesses, A (Alpha) and B (Bravo), each with an OWNER, an ADMIN and a VIEWER, their own
 * store, product, sale and import batch, deliberately using the same store code and SKU. Every
 * business-scoped endpoint is called by A's users and must only ever show A's data; anything aimed
 * at B (header, path id or write) must fail with 404/403 and change nothing.
 *
 * <p>Every B fixture carries the marker "bravo" (names, receipt, file name, member emails), so a
 * leak shows up in any response body.
 */
class BusinessIsolationIntegrationTest extends PostgresIntegrationTest {

    private static final String WINDOW = "from=2026-06-01&to=2026-06-30";
    private static final String CSV = """
            store_code,receipt_number,sold_at,sku,quantity,unit_price
            S1,NEW-1,2026-06-05T10:00:00Z,SKU-1,1,5.00
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    SqlFixture db;
    long a;
    long b;
    long storeA;
    long storeB;
    long productA;
    long productB;
    long saleA;
    long saleB;
    long batchA;
    long batchB;
    TestUser aOwner;
    TestUser aAdmin;
    TestUser aViewer;
    TestUser bOwner;
    TestUser bAdmin;
    TestUser bViewer;
    TestUser both;
    TestUser nobody;

    @BeforeEach
    void loadFixture() {
        db = new SqlFixture(jdbc);
        db.clear();
        TestAccounts accounts = new TestAccounts(jdbc);

        a = db.business("Alpha Co", "alpha-co", "EUR", "UTC");
        storeA = db.store(a, "S1", "Alpha Store", "Paris");
        productA = db.product(a, "SKU-1", "Alpha Widget", "AlphaCat", "100.00");
        saleA = db.sale(storeA, "RA-1", "2026-06-01T10:00:00Z", productA, 1, "100.00");
        batchA = db.importBatch(a, "alpha.csv", "100.00", saleA);

        b = db.business("Bravo Co", "bravo-co", "USD", "UTC");
        storeB = db.store(b, "S1", "Bravo Store", "Bravoville");
        productB = db.product(b, "SKU-1", "Bravo Widget", "BravoCat", "777.00");
        saleB = db.sale(storeB, "RB-1", "2026-06-02T10:00:00Z", productB, 1, "777.00");
        batchB = db.importBatch(b, "bravo.csv", "777.00", saleB);

        aOwner = accounts.member("a-owner@alpha.test", a, Role.OWNER);
        aAdmin = accounts.member("a-admin@alpha.test", a, Role.ADMIN);
        aViewer = accounts.member("a-viewer@alpha.test", a, Role.VIEWER);
        bOwner = accounts.member("b-owner@bravo.test", b, Role.OWNER);
        bAdmin = accounts.member("b-admin@bravo.test", b, Role.ADMIN);
        bViewer = accounts.member("b-viewer@bravo.test", b, Role.VIEWER);
        both = accounts.member("both@example.test", a, Role.OWNER);
        accounts.member(both, b, Role.VIEWER);
        nobody = accounts.user("nobody@example.test");
    }

    /** Every business-scoped read, with A's path ids. */
    private List<String> readsOf(long business, long store, long product, long sale, long batch) {
        return List.of(
                "/api/dashboard/context",
                "/api/dashboard/summary?" + WINDOW,
                "/api/dashboard/revenue?" + WINDOW,
                "/api/dashboard/sales-by-store?" + WINDOW,
                "/api/dashboard/top-products?" + WINDOW,
                "/api/dashboard/recent-sales?" + WINDOW,
                "/api/products?" + WINDOW,
                "/api/products/categories",
                "/api/products/" + product + "?" + WINDOW,
                "/api/products/" + product + "/sales-trend?" + WINDOW,
                "/api/sales?" + WINDOW,
                "/api/sales/" + sale,
                "/api/stores?" + WINDOW,
                "/api/stores/" + store + "?" + WINDOW,
                "/api/stores/" + store + "/revenue?" + WINDOW,
                "/api/stores/" + store + "/top-products?" + WINDOW,
                "/api/reports/monthly?" + WINDOW,
                "/api/reports/categories?" + WINDOW,
                "/api/reports/monthly.csv?" + WINDOW,
                "/api/reports/categories.csv?" + WINDOW,
                "/api/imports",
                "/api/imports/" + batch,
                "/api/businesses/" + business + "/members");
    }

    private static boolean adminOnly(String path) {
        return path.startsWith("/api/imports") || path.endsWith("/members");
    }

    private Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("businesses", "stores", "products", "sales", "sale_items", "import_batches", "memberships")) {
            counts.put(table, db.count(table));
        }
        return counts;
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ reads

    @Test
    void eachRoleOfAReadsOnlyAsData() throws Exception {
        for (TestUser user : List.of(aOwner, aAdmin, aViewer)) {
            for (String path : readsOf(a, storeA, productA, saleA, batchA)) {
                MvcResult result = perform(get(path).with(as(user, a)));
                int expected = adminOnly(path) && user == aViewer ? 403 : 200;
                assertThat(result.getResponse().getStatus()).as("%s %s", user.email(), path).isEqualTo(expected);
                assertThat(body(result)).as("%s %s", user.email(), path).doesNotContain("bravo", "rb-1");
            }
        }
        mvc.perform(get("/api/dashboard/context").with(as(aViewer, a)))
                .andExpect(jsonPath("$.business.slug").value("alpha-co"))
                .andExpect(jsonPath("$.access.role").value("VIEWER"))
                .andExpect(jsonPath("$.access.readOnly").value(true));
        mvc.perform(get("/api/sales?" + WINDOW).with(as(aOwner, a)))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].receiptNumber").value("RA-1"));
        mvc.perform(get("/api/imports").with(as(aAdmin, a)))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].fileName").value("alpha.csv"));
    }

    @Test
    void businessListShowsOnlyOwnMemberships() throws Exception {
        MvcResult result = perform(get("/api/businesses").with(as(aOwner)));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result)).contains("alpha-co").doesNotContain("bravo");
        mvc.perform(get("/api/businesses").with(as(both)))
                .andExpect(jsonPath("$[*].slug").value(org.hamcrest.Matchers.contains("alpha-co", "bravo-co")))
                .andExpect(jsonPath("$[1].role").value("VIEWER"));
        mvc.perform(get("/api/businesses").with(as(nobody))).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void selectingAnotherBusinessIs404ForEveryRead() throws Exception {
        for (TestUser user : List.of(aOwner, aAdmin, aViewer)) {
            // Both A's and B's path ids: the selector alone must already fail.
            for (List<String> paths : List.of(readsOf(a, storeA, productA, saleA, batchA), readsOf(b, storeB, productB, saleB, batchB))) {
                for (String path : paths) {
                    if (path.equals("/api/businesses/" + a + "/members")) {
                        continue; // business endpoints are selected by their path id, not the header
                    }
                    MvcResult result = perform(get(path).with(as(user, b)));
                    assertThat(result.getResponse().getStatus()).as("%s %s", user.email(), path).isEqualTo(404);
                    assertThat(body(result)).as(path).doesNotContain("bravo", "rb-1", "777");
                }
            }
        }
    }

    @Test
    void bPathIdsAre404ForA() throws Exception {
        List<String> foreign = List.of(
                "/api/products/" + productB,
                "/api/products/" + productB + "/sales-trend",
                "/api/sales/" + saleB,
                "/api/sales?productId=" + productB,
                "/api/stores/" + storeB,
                "/api/stores/" + storeB + "/revenue",
                "/api/stores/" + storeB + "/top-products",
                "/api/dashboard/summary?storeId=" + storeB,
                "/api/products?storeId=" + storeB,
                "/api/reports/monthly.csv?storeId=" + storeB,
                "/api/imports/" + batchB,
                "/api/businesses/" + b + "/members");
        for (String path : foreign) {
            MvcResult result = perform(get(path).with(as(aOwner, a)));
            assertThat(result.getResponse().getStatus()).as(path).isEqualTo(404);
            assertThat(body(result)).as(path).doesNotContain("bravo", "rb-1", "777");
        }
    }

    @Test
    void unknownOrMalformedSelectorIs404() throws Exception {
        for (String header : List.of("999999", "0", "-1", "abc", "", String.valueOf(b) + " ")) {
            mvc.perform(get("/api/dashboard/context").with(as(aOwner)).header(TestAccounts.BUSINESS_HEADER, header))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Business not found."));
        }
    }

    @Test
    void userWithoutMembershipsHasNoBusiness() throws Exception {
        mvc.perform(get("/api/dashboard/context").with(as(nobody))).andExpect(status().isNotFound());
        mvc.perform(get("/api/dashboard/context").with(as(nobody, a))).andExpect(status().isNotFound());
    }

    @Test
    void memberOfBothSeesEachOnlyWhenSelected() throws Exception {
        mvc.perform(get("/api/dashboard/context").with(as(both)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Select a business (X-Business-Id)."));

        MvcResult inA = perform(get("/api/sales?" + WINDOW).with(as(both, a)));
        assertThat(body(inA)).contains("ra-1").doesNotContain("rb-1");
        MvcResult inB = perform(get("/api/sales?" + WINDOW).with(as(both, b)));
        assertThat(body(inB)).contains("rb-1").doesNotContain("ra-1");

        // A's ids are not visible while B is selected, and vice versa.
        mvc.perform(get("/api/sales/" + saleA).with(as(both, b))).andExpect(status().isNotFound());
        mvc.perform(get("/api/sales/" + saleB).with(as(both, a))).andExpect(status().isNotFound());

        // The role is per business: OWNER in A, VIEWER in B.
        mvc.perform(get("/api/dashboard/context").with(as(both, b))).andExpect(jsonPath("$.access.role").value("VIEWER"));
        Map<String, Long> before = counts();
        mvc.perform(post("/api/stores").with(as(both, b)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"NEW\",\"name\":\"New\"}"))
                .andExpect(status().isForbidden());
        assertThat(counts()).isEqualTo(before);
        mvc.perform(post("/api/stores").with(as(both, a)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"NEW\",\"name\":\"New\"}"))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT business_id FROM stores WHERE code = 'NEW'", Long.class)).isEqualTo(a);
    }

    // ------------------------------------------------------------------ writes

    @Test
    void writesAimedAtBChangeNothing() throws Exception {
        Map<String, Long> before = counts();
        String bName = jdbc.queryForObject("SELECT name || time_zone FROM businesses WHERE id = ?", String.class, b);
        String roles = jdbc.queryForList("SELECT user_id || role FROM memberships ORDER BY 1", String.class).toString();
        MockMultipartFile file = new MockMultipartFile("file", "x.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8));

        for (TestUser user : List.of(aOwner, aAdmin, aViewer)) {
            // B selected by header.
            mvc.perform(multipart("/api/imports").file(file).param("dryRun", "false").with(as(user, b)))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/stores").with(as(user, b)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"X1\",\"name\":\"X\"}")).andExpect(status().isNotFound());
            mvc.perform(post("/api/products").with(as(user, b)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sku\":\"X1\",\"name\":\"X\",\"category\":\"C\",\"listPrice\":1}")).andExpect(status().isNotFound());
            // B in the path.
            mvc.perform(patch("/api/businesses/" + b).with(as(user, a)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Hijacked\"}")).andExpect(status().isNotFound());
            mvc.perform(post("/api/businesses/" + b + "/members").with(as(user, a)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + user.email() + "\",\"role\":\"OWNER\"}")).andExpect(status().isNotFound());
            mvc.perform(patch("/api/businesses/" + b + "/members/" + bOwner.id()).with(as(user, a))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}")).andExpect(status().isNotFound());
            mvc.perform(delete("/api/businesses/" + b + "/members/" + bViewer.id()).with(as(user, a)))
                    .andExpect(status().isNotFound());
            // A in the path, B's member as the target.
            mvc.perform(delete("/api/businesses/" + a + "/members/" + bViewer.id()).with(as(user, a)))
                    .andExpect(status().is(user == aViewer ? 403 : 404));
        }
        mvc.perform(patch("/api/businesses/" + a + "/members/" + bAdmin.id()).with(as(aOwner, a))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}")).andExpect(status().isNotFound());

        assertThat(counts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT name || time_zone FROM businesses WHERE id = ?", String.class, b)).isEqualTo(bName);
        assertThat(jdbc.queryForList("SELECT user_id || role FROM memberships ORDER BY 1", String.class).toString()).isEqualTo(roles);
    }

    @Test
    void writesByAGoOnlyIntoA() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8));
        // S1 and SKU-1 exist in both businesses: the import must resolve them in A only.
        mvc.perform(multipart("/api/imports").file(file).param("dryRun", "false").with(as(aAdmin, a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IMPORTED"));
        assertThat(jdbc.queryForObject("""
                SELECT st.business_id FROM sales s JOIN stores st ON st.id = s.store_id WHERE s.receipt_number = 'NEW-1'
                """, Long.class)).isEqualTo(a);
        assertThat(jdbc.queryForObject("""
                SELECT p.business_id FROM sale_items si JOIN sales s ON s.id = si.sale_id
                JOIN products p ON p.id = si.product_id WHERE s.receipt_number = 'NEW-1'
                """, Long.class)).isEqualTo(a);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_batches WHERE business_id = ?", Long.class, b)).isEqualTo(1);

        // Same file into B by B's admin: duplicate detection is per business.
        mvc.perform(multipart("/api/imports").file(file).param("dryRun", "false").with(as(bAdmin, b)))
                .andExpect(jsonPath("$.status").value("IMPORTED"));
        // The same store code and SKU may exist in each business.
        mvc.perform(post("/api/stores").with(as(aAdmin, a)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"S2\",\"name\":\"Second\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/stores").with(as(bAdmin, b)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"S2\",\"name\":\"Second\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/products").with(as(bAdmin, b)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"SKU-2\",\"name\":\"W\",\"category\":\"C\",\"listPrice\":2.5}")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT business_id FROM products WHERE sku = 'SKU-2'", Long.class)).isEqualTo(b);
    }
}
