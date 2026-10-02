package com.oussamaksantini.insightstudio.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;

/**
 * Stores, products and mapped sales imports (docs/catalog-imports-contract.md) against PostgreSQL,
 * called by the OWNER of "Test Co", time zone America/New_York.
 * <pre>
 * Test Co:  stores BOS (Back Bay, Boston), WEB (Online, no city)
 *           products TEE-1 (Tee, Tops, 25.00), JNS-1 (Jeans, Bottoms, 98.00)
 *           receipt R-1 at BOS on 2026-09-01: TEE-1 2 x 20.00, JNS-1 1 x 90.00 = 130.00
 * Other Co: store OTH (Other store, Lyon), product OTH-1 (Other, Misc, 10.00)
 * </pre>
 */
class CatalogImportApiIntegrationTest extends PostgresIntegrationTest {

    private static final String PERIOD = "from=2026-08-25&to=2026-09-05";
    private static final String STORES = "code,name,city\n";
    private static final String PRODUCTS = "sku,name,category,list_price\n";
    private static final String SALES = "store_code,receipt_number,sold_at,sku,quantity,unit_price\n";

    @Autowired
    WebApplicationContext context;

    /** Without any authentication. */
    @Autowired
    MockMvc anonymous;

    @Autowired
    JdbcTemplate jdbc;

    TestAccounts accounts;
    MockMvc mvc;
    TestUser owner;
    long business;
    long otherBusiness;
    long tee;
    long jeans;

    @BeforeEach
    void loadFixture() {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();
        accounts = new TestAccounts(jdbc);
        business = db.business("Test Co", "test-co", "USD", "America/New_York");
        owner = accounts.member("owner@example.com", business, Role.OWNER);
        mvc = TestAccounts.mvc(context, owner, business);
        long bos = db.store(business, "BOS", "Back Bay", "Boston");
        db.store(business, "WEB", "Online", null);
        tee = db.product(business, "TEE-1", "Tee", "Tops", "25.00");
        jeans = db.product(business, "JNS-1", "Jeans", "Bottoms", "98.00");
        db.sale(bos, "R-1", "2026-09-01T15:00:00Z", tee, 2, "20.00", jeans, 1, "90.00");

        otherBusiness = db.business("Other Co", "other-co", "EUR", "UTC");
        db.store(otherBusiness, "OTH", "Other store", "Lyon");
        db.product(otherBusiness, "OTH-1", "Other", "Misc", "10.00");
    }

    // ---------------------------------------------------------------- helpers

    private static ResultActions send(MockMvc client, String path, String fileName, String csv, String... params)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(path)
                .file(new MockMultipartFile("file", fileName, "text/csv", csv.getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return client.perform(request);
    }

    /** A real import ({@code dryRun=false}); {@code params} adds mapping and mode. */
    private ResultActions importCsv(String kind, String csv, String... params) throws Exception {
        List<String> all = new ArrayList<>(List.of(params));
        all.addAll(List.of("dryRun", "false"));
        return send(mvc, "/api/imports/" + kind, kind + ".csv", csv, all.toArray(String[]::new));
    }

    private ResultActions validateCsv(String kind, String csv, String... params) throws Exception {
        return send(mvc, "/api/imports/" + kind, kind + ".csv", csv, params);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private Map<String, Object> store(String code) {
        return jdbc.queryForMap("SELECT name, city FROM stores WHERE business_id = ? AND code = ?", business, code);
    }

    private Map<String, Object> product(String sku) {
        return jdbc.queryForMap("SELECT name, category, list_price FROM products WHERE business_id = ? AND sku = ?",
                business, sku);
    }

    /** Everything an import could write, plus the history; equal before and after means nothing was written. */
    private List<Object> catalogue() {
        return List.of(
                jdbc.queryForList("SELECT business_id, code, name, city FROM stores ORDER BY id"),
                jdbc.queryForList("SELECT business_id, sku, name, category, list_price FROM products ORDER BY id"),
                jdbc.queryForList("SELECT id, store_id, receipt_number FROM sales ORDER BY id"),
                jdbc.queryForList("SELECT sale_id, product_id, quantity, unit_price FROM sale_items ORDER BY id"));
    }

    private long dataVersion() {
        return count("SELECT version FROM report_data_version WHERE id = 1");
    }

    private String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    // ---------------------------------------------------------------- templates

    @Test
    void templatesHaveTheHeaderInFieldOrderAndExampleRows() throws Exception {
        mvc.perform(get("/api/imports/templates/stores.csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"stores-template.csv\""))
                .andExpect(content().string("code,name,city\r\nBOS,Back Bay,Boston\r\nCAM,Harvard Square,Cambridge\r\n"
                        + "WEB,Online store,\r\n"));
        mvc.perform(get("/api/imports/templates/products.csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"products-template.csv\""))
                .andExpect(content().string(startsWith("sku,name,category,list_price\r\nTEE-001,Classic tee,Tops,24.50\r\n")));
        mvc.perform(get("/api/imports/templates/sales.csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"sales-template.csv\""))
                .andExpect(content().string(startsWith("store_code,receipt_number,sold_at,sku,quantity,unit_price\r\n")));
        mvc.perform(get("/api/imports/templates/customers.csv")).andExpect(status().isNotFound());
    }

    @Test
    void theTemplatesImportCleanlyIntoAnEmptyBusinessInOrder() throws Exception {
        long empty = new SqlFixture(jdbc).business("Empty Co", "empty-co", "USD", "America/New_York");
        MockMvc emptyOwner = TestAccounts.mvc(context, accounts.member("empty@example.com", empty, Role.OWNER), empty);

        for (String kind : List.of("stores", "products", "sales")) {
            String template = emptyOwner.perform(get("/api/imports/templates/" + kind + ".csv"))
                    .andReturn().getResponse().getContentAsString();
            send(emptyOwner, "/api/imports/" + kind, kind + "-template.csv", template, "dryRun", "false")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IMPORTED"))
                    .andExpect(jsonPath("$.kind").value(kind))
                    .andExpect(jsonPath("$.errorCount").value(0))
                    .andExpect(jsonPath("$.created").value(kind.equals("sales") ? 2 : 3));
        }
        assertThat(count("SELECT COUNT(*) FROM stores WHERE business_id = ?", empty)).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM products WHERE business_id = ?", empty)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT SUM(quantity * unit_price) FROM sale_items WHERE business_id = ?",
                BigDecimal.class, empty)).isEqualByComparingTo("164.10");
    }

    // ---------------------------------------------------------------- preview and mapping

    @Test
    void previewShowsTheFileAndSuggestsAMapping() throws Exception {
        StringBuilder csv = new StringBuilder("Store ID , STORE name,Town,Notes\n");
        for (int i = 1; i <= 12; i++) {
            csv.append("S").append(i).append(",Store ").append(i).append(",City,\n");
        }
        List<Object> before = catalogue();
        send(mvc, "/api/imports/stores/preview", "stores.csv", csv.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("stores"))
                .andExpect(jsonPath("$.fileName").value("stores.csv"))
                .andExpect(jsonPath("$.rowCount").value(12))
                .andExpect(jsonPath("$.columns", contains("Store ID", "STORE name", "Town", "Notes")))
                .andExpect(jsonPath("$.sampleRows", hasSize(10)))
                .andExpect(jsonPath("$.sampleRows[0]", contains("S1", "Store 1", "City", "")))
                .andExpect(jsonPath("$.fields[*].name", contains("code", "name", "city")))
                .andExpect(jsonPath("$.fields[*].required", contains(true, true, false)))
                .andExpect(jsonPath("$.fields[0].label").value("Store code"))
                .andExpect(jsonPath("$.fields[0].description").isNotEmpty())
                .andExpect(jsonPath("$.suggestedMapping.code").value("Store ID"))
                .andExpect(jsonPath("$.suggestedMapping.name").value("STORE name"))
                .andExpect(jsonPath("$.suggestedMapping.city").value("Town"));

        send(mvc, "/api/imports/products/preview", "p.csv", "Product Code,Title,Department,Price\nA,B,C,1\n")
                .andExpect(jsonPath("$.suggestedMapping.sku").value("Product Code"))
                .andExpect(jsonPath("$.suggestedMapping.name").value("Title"))
                .andExpect(jsonPath("$.suggestedMapping.category").value("Department"))
                .andExpect(jsonPath("$.suggestedMapping.list_price").value("Price"));
        send(mvc, "/api/imports/sales/preview", "s.csv", "Shop,Receipt,Date,SKU,Qty,Notes\nA,B,C,D,1,x\n")
                .andExpect(jsonPath("$.suggestedMapping.store_code").value("Shop"))
                .andExpect(jsonPath("$.suggestedMapping.sold_at").value("Date"))
                .andExpect(jsonPath("$.suggestedMapping.quantity").value("Qty"))
                // Every field is listed, null when nothing fits.
                .andExpect(jsonPath("$.suggestedMapping.unit_price").value(nullValue()));

        send(mvc, "/api/imports/stores/preview", "stores.csv", "").andExpect(status().isBadRequest());
        send(mvc, "/api/imports/stores/preview", "stores.csv", "code,name\n")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The file has a header but no data rows."));
        send(mvc, "/api/imports/customers/preview", "c.csv", STORES + "A,B,C\n").andExpect(status().isNotFound());
        assertThat(catalogue()).isEqualTo(before);
        assertThat(count("SELECT COUNT(*) FROM import_batches")).isZero();
    }

    @Test
    void mappingProblemsRejectTheFileBeforeAnyRow() throws Exception {
        String csv = "Store ID,Label,Town\nNEW,New,Boston\n";
        importCsv("stores", csv, "mapping", """
                {"code": "Store ID", "name": "Store ID", "city": "Ville", "colour": "Label"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errorCount").value(3))
                .andExpect(jsonPath("$.errors[*].line", contains(nullValue(), nullValue(), nullValue())))
                .andExpect(jsonPath("$.errors[0].field").value(nullValue()))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("Unknown field 'colour'")))
                .andExpect(jsonPath("$.errors[1].field").value("name"))
                .andExpect(jsonPath("$.errors[1].column").value("Store ID"))
                .andExpect(jsonPath("$.errors[1].message").value(containsString("already mapped to 'code'")))
                .andExpect(jsonPath("$.errors[2].field").value("city"))
                .andExpect(jsonPath("$.errors[2].column").value("Ville"))
                .andExpect(jsonPath("$.errors[2].message").value("Column 'Ville' (mapped to 'city') is not in the file."));
        validateCsv("stores", csv, "mapping", "{\"code\": \"Store ID\"}")
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].column").value(nullValue()))
                .andExpect(jsonPath("$.errors[0].message").value("Required field 'name' (Store name) is not mapped to a column."));
        // Without a mapping, fields read the columns of the same name.
        validateCsv("stores", csv)
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[*].field", contains("code", "name")));
        assertThat(count("SELECT COUNT(*) FROM stores WHERE code = 'NEW'")).isZero();

        // A malformed mapping or mode is a bad request.
        for (String mapping : List.of("not json", "[1, 2]", "{\"code\": 5}")) {
            validateCsv("stores", csv, "mapping", mapping)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(startsWith("'mapping' must be a JSON object")));
        }
        validateCsv("stores", STORES + "A,B,C\n", "mode", "upsert").andExpect(status().isBadRequest());
        validateCsv("sales", SALES + "BOS,R-9,2026-09-01T10:00:00Z,TEE-1,1,1.00\n", "mode", "create_or_update")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("create_only")));
        validateCsv("customers", STORES + "A,B,C\n").andExpect(status().isNotFound());
    }

    @Test
    void mappedImportsReadRenamedReorderedAndExtraColumns() throws Exception {
        importCsv("stores", "Town,Notes,Store name,Store ID\nProvidence,ignored,Downtown,PVD\n,,Pop-up,POP\n",
                "mapping", "{\"code\": \"Store ID\", \"name\": \"Store name\", \"city\": \"Town\"}")
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.mode").value("create_only"))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.saleCount").value(0))
                .andExpect(jsonPath("$.totalAmount").value(0));
        assertThat(store("PVD")).containsEntry("name", "Downtown").containsEntry("city", "Providence");
        assertThat(store("POP")).containsEntry("name", "Pop-up").containsEntry("city", null);

        importCsv("products", "Price,SKU code,Dept,Title,Internal\n12.5,CAP-1,Hats,Cap,x\n0,FREE-1,Gifts,Sticker,y\n",
                "mapping", "{\"sku\": \"SKU code\", \"name\": \"Title\", \"category\": \"Dept\", \"list_price\": \"Price\"}")
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.created").value(2));
        assertThat(product("CAP-1")).containsEntry("name", "Cap").containsEntry("category", "Hats");
        assertThat((BigDecimal) product("CAP-1").get("list_price")).isEqualByComparingTo("12.50");

        importCsv("sales", "Qty,When,Item,Shop,Ticket,Paid,Comment\n3,2026-09-02T10:00:00Z,CAP-1,PVD,T-1,11.00,hi\n",
                "mapping", """
                        {"store_code": "Shop", "receipt_number": "Ticket", "sold_at": "When", "sku": "Item",
                         "quantity": "Qty", "unit_price": "Paid"}
                        """)
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.saleCount").value(1))
                .andExpect(jsonPath("$.totalAmount").value(33.00));
        assertThat(jdbc.queryForMap("""
                SELECT st.code, si.quantity, si.unit_price FROM sale_items si
                JOIN sales s ON s.id = si.sale_id JOIN stores st ON st.id = s.store_id WHERE s.receipt_number = 'T-1'
                """)).containsEntry("code", "PVD").containsEntry("quantity", 3)
                .containsEntry("unit_price", new BigDecimal("11.00"));
    }

    @Test
    void rowErrorsNameTheFieldAndTheSourceColumn() throws Exception {
        importCsv("products", "Ref,Title,Dept,Price\nbad sku,,Tops,1.999\nOK-1,Fine,Tops,-1\n",
                "mapping", "{\"sku\": \"Ref\", \"name\": \"Title\", \"category\": \"Dept\", \"list_price\": \"Price\"}")
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errorCount").value(4))
                .andExpect(jsonPath("$.errors[*].line", contains(2, 2, 2, 3)))
                .andExpect(jsonPath("$.errors[*].field", contains("sku", "name", "list_price", "list_price")))
                .andExpect(jsonPath("$.errors[*].column", contains("Ref", "Title", "Price", "Price")))
                // The messages of POST /api/products.
                .andExpect(jsonPath("$.errors[0].message").value(
                        "'sku' must be 1 to 50 letters, digits, '.', '_' or '-', starting with a letter or digit."))
                .andExpect(jsonPath("$.errors[1].message").value("'name' is required."))
                .andExpect(jsonPath("$.errors[2].message").value("'list_price' must be a price of 0 or more with at most 2 decimals."));
        importCsv("stores", STORES + "TOO-LONG," + "x".repeat(201) + ",Boston\nX,Short,\n,Name,City\n")
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].message").value("'name' must be at most 200 characters of text."))
                .andExpect(jsonPath("$.errors[1].line").value(4))
                .andExpect(jsonPath("$.errors[1].field").value("code"))
                .andExpect(jsonPath("$.errors[1].column").value("code"));
    }

    // ---------------------------------------------------------------- modes

    @Test
    void createOnlyRejectsKeysThatExistInTheBusiness() throws Exception {
        List<Object> before = catalogue();
        importCsv("stores", "Store ID,Name\nNEW,New one\nBOS,Back Bay\n", "mapping", "{\"code\": \"Store ID\", \"name\": \"Name\"}")
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.mode").value("create_only"))
                .andExpect(jsonPath("$.errorCount").value(1))
                .andExpect(jsonPath("$.errors[0].line").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("code"))
                .andExpect(jsonPath("$.errors[0].column").value("Store ID"))
                .andExpect(jsonPath("$.errors[0].message").value(
                        "Store code 'BOS' already exists. Choose “Create and update” to change it."))
                // The counts cover the rows without errors.
                .andExpect(jsonPath("$.created").value(1));
        importCsv("products", PRODUCTS + "TEE-1,Tee,Tops,25.00\n", "mode", "create_only")
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].message").value("SKU 'TEE-1' already exists. Choose “Create and update” to change it."));
        // Codes are matched exactly: "bos" is a new store.
        validateCsv("stores", STORES + "bos,Lower case,\n").andExpect(jsonPath("$.status").value("VALIDATED"));
        assertThat(catalogue()).isEqualTo(before);
    }

    @Test
    void createOrUpdateCreatesUpdatesAndCountsUnchangedRows() throws Exception {
        String csv = STORES + "BOS,Back Bay,Boston\nWEB,Online shop,\nNEW,New one,Providence\n";
        List<Object> before = catalogue();
        validateCsv("stores", csv, "mode", "create_or_update")
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.mode").value("create_or_update"))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.unchanged").value(1))
                .andExpect(jsonPath("$.categoryChanges").value(0));
        assertThat(catalogue()).isEqualTo(before);

        importCsv("stores", csv, "mode", "create_or_update")
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.unchanged").value(1));
        assertThat(store("BOS")).containsEntry("name", "Back Bay").containsEntry("city", "Boston");
        assertThat(store("WEB")).containsEntry("name", "Online shop").containsEntry("city", null);
        assertThat(store("NEW")).containsEntry("name", "New one").containsEntry("city", "Providence");
        assertThat(jdbc.queryForMap("SELECT created_count, updated_count, unchanged_count, mode FROM import_batches"))
                .containsEntry("created_count", 1).containsEntry("updated_count", 1)
                .containsEntry("unchanged_count", 1).containsEntry("mode", "create_or_update");
    }

    @Test
    void anUnmappedCityIsLeftAloneWhileAMappedEmptyCityIsCleared() throws Exception {
        importCsv("stores", "code,name\nBOS,Back Bay 2\nNEW,New one\n", "mode", "create_or_update")
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.created").value(1));
        assertThat(store("BOS")).containsEntry("name", "Back Bay 2").containsEntry("city", "Boston");
        assertThat(store("NEW")).containsEntry("city", null);

        importCsv("stores", STORES + "BOS,Back Bay 2,\n", "mode", "create_or_update")
                .andExpect(jsonPath("$.updated").value(1));
        assertThat(store("BOS")).containsEntry("city", null);
        // Mapped explicitly to null: not mapped, so left alone.
        jdbc.update("UPDATE stores SET city = 'Boston' WHERE code = 'BOS'");
        importCsv("stores", STORES + "BOS,Back Bay 3,Somewhere\n", "mode", "create_or_update",
                "mapping", "{\"code\": \"code\", \"name\": \"name\", \"city\": null}")
                .andExpect(jsonPath("$.updated").value(1));
        assertThat(store("BOS")).containsEntry("name", "Back Bay 3").containsEntry("city", "Boston");
    }

    @Test
    void aKeyRepeatedInTheFileIsAnErrorOnTheLaterRows() throws Exception {
        importCsv("stores", STORES + "NEW,One,\nNEW,Two,\nNEW,Three,\n", "mode", "create_or_update")
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[*].line", contains(3, 4)))
                .andExpect(jsonPath("$.errors[0].message").value("Duplicate store code 'NEW' in this file (line 2)."));
        importCsv("products", PRODUCTS + "TEE-1,Tee,Tops,25.00\nTEE-1,Tee,Tops,26.00\n", "mode", "create_or_update")
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].line").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("sku"))
                .andExpect(jsonPath("$.errors[0].message").value("Duplicate SKU 'TEE-1' in this file (line 2)."));
        assertThat(count("SELECT COUNT(*) FROM stores WHERE code = 'NEW'")).isZero();
    }

    @Test
    void productUpdatesNeverChangeHistoricalRevenueButCategoriesMove() throws Exception {
        List<Map<String, Object>> itemsBefore = jdbc.queryForList("SELECT id, product_id, quantity, unit_price FROM sale_items ORDER BY id");
        String summaryBefore = body(mvc.perform(get("/api/dashboard/summary?" + PERIOD)));
        String categoriesBefore = body(mvc.perform(get("/api/reports/categories?" + PERIOD)));
        assertThat(JsonPath.<List<Double>>read(categoriesBefore, "$.rows[?(@.category == 'Bottoms')].revenue")).containsExactly(90.0);

        // New list price and name for TEE-1; JNS-1 moves from Bottoms to Denim.
        String csv = PRODUCTS + "TEE-1,Tee (organic),Tops,99.00\nJNS-1,Jeans,Denim,98.00\n";
        validateCsv("products", csv, "mode", "create_or_update")
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.categoryChanges").value(1));
        importCsv("products", csv, "mode", "create_or_update")
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.unchanged").value(0))
                .andExpect(jsonPath("$.categoryChanges").value(1));

        assertThat(product("TEE-1")).containsEntry("name", "Tee (organic)").containsEntry("list_price", new BigDecimal("99.00"));
        // The prices actually charged, and so every revenue figure, are untouched.
        assertThat(jdbc.queryForList("SELECT id, product_id, quantity, unit_price FROM sale_items ORDER BY id")).isEqualTo(itemsBefore);
        String summaryAfter = body(mvc.perform(get("/api/dashboard/summary?" + PERIOD)));
        assertThat(JsonPath.<Object>read(summaryAfter, "$.revenue.value")).isEqualTo(JsonPath.read(summaryBefore, "$.revenue.value"));
        String categoriesAfter = body(mvc.perform(get("/api/reports/categories?" + PERIOD)));
        assertThat(JsonPath.<Object>read(categoriesAfter, "$.totals.revenue")).isEqualTo(JsonPath.read(categoriesBefore, "$.totals.revenue"));
        assertThat(JsonPath.<List<Double>>read(categoriesAfter, "$.rows[?(@.category == 'Tops')].revenue")).containsExactly(40.0);
        // Category is a current attribute: the jeans' past sales now count as Denim.
        assertThat(JsonPath.<List<Double>>read(categoriesAfter, "$.rows[?(@.category == 'Denim')].revenue")).containsExactly(90.0);
        assertThat(JsonPath.<List<String>>read(categoriesAfter, "$.rows[*].category")).doesNotContain("Bottoms");
    }

    // ---------------------------------------------------------------- all or nothing

    @Test
    void oneBadRowMeansNothingIsWrittenForAnyKind() throws Exception {
        List<Object> before = catalogue();
        importCsv("stores", STORES + "NEW,New one,\nBOS,Changed,\nBAD CODE,Name,\n", "mode", "create_or_update")
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errorCount").value(1));
        importCsv("products", PRODUCTS + "NEW-1,New,Misc,1.00\nTEE-1,Tee,Shirts,25.00\nNEW-2,Broken,Misc,free\n",
                "mode", "create_or_update")
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errorCount").value(1));
        importCsv("sales", "Shop,Ticket,When,Item,Qty,Paid\nBOS,N-1,2026-09-02T10:00:00Z,TEE-1,1,5.00\n"
                        + "BOS,N-2,2026-09-02T11:00:00Z,TEE-1,0,5.00\n",
                "mapping", """
                        {"store_code": "Shop", "receipt_number": "Ticket", "sold_at": "When", "sku": "Item",
                         "quantity": "Qty", "unit_price": "Paid"}
                        """)
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].field").value("quantity"))
                .andExpect(jsonPath("$.errors[0].column").value("Qty"));
        assertThat(catalogue()).isEqualTo(before);
        // Each attempt is in the history, rejected, with its error count and no counts.
        assertThat(jdbc.queryForList("SELECT kind, status, error_count, created_count, updated_count FROM import_batches ORDER BY id"))
                .extracting(r -> r.get("kind") + " " + r.get("status") + " " + r.get("error_count") + " "
                        + r.get("created_count") + " " + r.get("updated_count"))
                .containsExactly("stores REJECTED 1 0 0", "products REJECTED 1 0 0", "sales REJECTED 1 0 0");
    }

    @Test
    void aFailureWhileWritingRollsTheWholeFileBack() throws Exception {
        // Creating the products succeeds; updating TEE-1 then fails inside the import transaction.
        jdbc.execute("""
                CREATE FUNCTION test_fail_on_name() RETURNS trigger AS $$
                BEGIN
                  IF NEW.name = 'Boom' THEN RAISE EXCEPTION 'simulated failure'; END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql
                """);
        jdbc.execute("CREATE TRIGGER test_fail BEFORE UPDATE ON products FOR EACH ROW EXECUTE FUNCTION test_fail_on_name()");
        String csv = PRODUCTS + "NEW-1,New,Misc,1.00\nTEE-1,Boom,Tops,25.00\n";
        try {
            List<Object> before = catalogue();
            importCsv("products", csv, "mode", "create_or_update")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"))
                    .andExpect(jsonPath("$.batchId").value(nullValue()))
                    .andExpect(jsonPath("$.created").value(0))
                    .andExpect(jsonPath("$.errors[0].message").value(ImportService.IMPORT_FAILED));
            assertThat(catalogue()).isEqualTo(before);
            assertThat(jdbc.queryForMap("SELECT kind, status, error_count, created_by FROM import_batches"))
                    .containsEntry("kind", "products").containsEntry("status", "REJECTED")
                    .containsEntry("error_count", 1).containsEntry("created_by", owner.id());
        } finally {
            jdbc.execute("DROP TRIGGER test_fail ON products");
            jdbc.execute("DROP FUNCTION test_fail_on_name()");
        }
        // Nothing was imported, so the same file still can be.
        importCsv("products", csv, "mode", "create_or_update").andExpect(jsonPath("$.status").value("IMPORTED"));
    }

    @Test
    void aConstraintViolationWhileWritingIsARejectionNotAServerError() throws Exception {
        // Simulates another writer creating the same SKU after validation, inside the import transaction.
        jdbc.execute("""
                CREATE FUNCTION test_race() RETURNS trigger AS $$
                BEGIN
                  INSERT INTO products (business_id, sku, name, category, list_price)
                  VALUES (NEW.business_id, 'RACE-1', 'Created meanwhile', 'Misc', 1.00);
                  RETURN NULL;
                END $$ LANGUAGE plpgsql
                """);
        jdbc.execute("CREATE TRIGGER test_race AFTER INSERT ON import_batches FOR EACH ROW "
                + "WHEN (NEW.status = 'IMPORTED') EXECUTE FUNCTION test_race()");
        try {
            importCsv("products", PRODUCTS + "RACE-1,Racer,Sport,10.00\n")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"))
                    .andExpect(jsonPath("$.errors[0].message").value(ImportService.CHANGED_WHILE_IMPORTING));
        } finally {
            jdbc.execute("DROP TRIGGER test_race ON import_batches");
            jdbc.execute("DROP FUNCTION test_race()");
        }
        assertThat(count("SELECT COUNT(*) FROM products WHERE sku = 'RACE-1'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM import_batches WHERE status = 'REJECTED'")).isEqualTo(1);
    }

    @Test
    void concurrentImportsOfTheSameNewSkuImportItOnce() throws Exception {
        MockMvc admin = TestAccounts.mvc(context, accounts.member("admin@example.com", business, Role.ADMIN), business);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            int i = 0;
            for (MockMvc client : List.of(mvc, admin)) {
                String csv = PRODUCTS + "RACE-1,Racer " + i++ + ",Sport,10.00\n";
                results.add(pool.submit(() -> {
                    start.await();
                    return send(client, "/api/imports/products", "race.csv", csv, "dryRun", "false")
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();
                }));
            }
            start.countDown();
            List<String> statuses = new ArrayList<>();
            for (Future<String> result : results) {
                String json = result.get(60, TimeUnit.SECONDS);
                String status = JsonPath.read(json, "$.status");
                statuses.add(status);
                if (status.equals("REJECTED")) {
                    assertThat(JsonPath.<String>read(json, "$.errors[0].message")).isIn(
                            "SKU 'RACE-1' already exists. Choose “Create and update” to change it.",
                            ImportService.CHANGED_WHILE_IMPORTING);
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder("IMPORTED", "REJECTED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM products WHERE sku = 'RACE-1'")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT status FROM import_batches WHERE kind = 'products' ORDER BY status", String.class))
                .containsExactly("IMPORTED", "REJECTED");
    }

    // ---------------------------------------------------------------- duplicate files

    @Test
    void theSameFileIsImportedOncePerKind() throws Exception {
        // Valid as stores and as products: each kind reads its own columns.
        String csv = "code,name,city,sku,category,list_price\nX1,Thing,Paris,X1,Misc,1.00\n";
        importCsv("stores", csv).andExpect(jsonPath("$.status").value("IMPORTED"));
        importCsv("products", csv).andExpect(jsonPath("$.status").value("IMPORTED"));

        String today = jdbc.queryForObject(
                "SELECT CAST(created_at AT TIME ZONE 'America/New_York' AS date) FROM import_batches ORDER BY id LIMIT 1",
                String.class);
        for (boolean dryRun : new boolean[] {true, false}) {
            send(mvc, "/api/imports/stores", "renamed.csv", csv, "mode", "create_or_update", "dryRun", String.valueOf(dryRun))
                    .andExpect(jsonPath("$.status").value("REJECTED"))
                    .andExpect(jsonPath("$.errorCount").value(1))
                    .andExpect(jsonPath("$.errors[0].line").value(nullValue()))
                    .andExpect(jsonPath("$.errors[0].message").value(
                            "This file was already imported on %s (same content). Nothing was imported.".formatted(today)));
        }
        assertThat(jdbc.queryForList("SELECT kind || ' ' || status FROM import_batches ORDER BY id", String.class))
                .containsExactly("stores IMPORTED", "products IMPORTED", "stores REJECTED");
    }

    @Test
    void aRejectedAttemptDoesNotBlockARetryOfTheSameFile() throws Exception {
        String sales = SALES + "PVD,R-9,2026-09-02T10:00:00Z,TEE-1,1,5.00\n";
        importCsv("sales", sales)
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].message").value("Unknown store code 'PVD'."));
        importCsv("stores", STORES + "PVD,Providence,Providence\n").andExpect(jsonPath("$.status").value("IMPORTED"));
        importCsv("sales", sales).andExpect(jsonPath("$.status").value("IMPORTED"));
    }

    // ---------------------------------------------------------------- isolation and access

    @Test
    void importsOnlySeeAndChangeTheCurrentBusiness() throws Exception {
        // OTH and OTH-1 exist only in Other Co: here they are new, and Other Co's are never updated.
        importCsv("stores", STORES + "OTH,Our OTH,Paris\n", "mode", "create_or_update")
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(0));
        importCsv("products", PRODUCTS + "OTH-1,Ours,Ours,2.00\n").andExpect(jsonPath("$.status").value("IMPORTED"));
        assertThat(jdbc.queryForMap("SELECT name, city FROM stores WHERE business_id = ? AND code = 'OTH'", otherBusiness))
                .containsEntry("name", "Other store").containsEntry("city", "Lyon");
        assertThat(jdbc.queryForMap("SELECT name, list_price FROM products WHERE business_id = ? AND sku = 'OTH-1'", otherBusiness))
                .containsEntry("name", "Other").containsEntry("list_price", new BigDecimal("10.00"));
        assertThat(store("OTH")).containsEntry("name", "Our OTH");

        // Other Co's history and batches are invisible here.
        MockMvc otherOwner = TestAccounts.mvc(context, accounts.member("other@example.com", otherBusiness, Role.OWNER), otherBusiness);
        String other = body(send(otherOwner, "/api/imports/stores", "s.csv", STORES + "ZZ,Zed,\n", "dryRun", "false"));
        long otherBatch = ((Number) JsonPath.read(other, "$.batchId")).longValue();
        mvc.perform(get("/api/imports"))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].batchId", not(hasItem((int) otherBatch))));
        mvc.perform(get("/api/imports/" + otherBatch)).andExpect(status().isNotFound());
        otherOwner.perform(get("/api/imports/" + otherBatch)).andExpect(status().isOk());
    }

    @Test
    void onlyVerifiedAdminsAndOwnersMayImportWithCsrf() throws Exception {
        MockMvc viewer = TestAccounts.mvc(context, accounts.member("viewer@example.com", business, Role.VIEWER), business);
        TestUser unverified = accounts.unverifiedUser("unverified@example.com");
        accounts.member(unverified, business, Role.ADMIN);
        MockMvc unverifiedAdmin = TestAccounts.mvc(context, unverified, business);
        List<Object> before = catalogue();

        for (MockMvc forbidden : List.of(viewer, unverifiedAdmin)) {
            send(forbidden, "/api/imports/stores", "s.csv", STORES + "NEW,New,\n", "dryRun", "false").andExpect(status().isForbidden());
            send(forbidden, "/api/imports/stores/preview", "s.csv", STORES + "NEW,New,\n").andExpect(status().isForbidden());
            send(forbidden, "/api/imports/stores/errors.csv", "s.csv", STORES + "NEW,New,\n").andExpect(status().isForbidden());
            forbidden.perform(get("/api/imports/templates/stores.csv")).andExpect(status().isForbidden());
            forbidden.perform(get("/api/imports?kind=stores")).andExpect(status().isForbidden());
        }
        anonymous.perform(multipart("/api/imports/stores")
                        .file(new MockMultipartFile("file", "s.csv", "text/csv", (STORES + "NEW,New,\n").getBytes(StandardCharsets.UTF_8)))
                        .param("dryRun", "false")
                        .with(TestAccounts.csrf()))
                .andExpect(status().isUnauthorized());
        anonymous.perform(get("/api/imports/templates/stores.csv")).andExpect(status().isUnauthorized());
        // A signed-in owner without a valid CSRF token is refused too.
        for (var csrf : List.of(TestAccounts.invalidCsrf(), (RequestPostProcessor) request -> request)) {
            anonymous.perform(multipart("/api/imports/stores")
                            .file(new MockMultipartFile("file", "s.csv", "text/csv", (STORES + "NEW,New,\n").getBytes(StandardCharsets.UTF_8)))
                            .param("dryRun", "false")
                            .header(TestAccounts.BUSINESS_HEADER, business)
                            .with(TestAccounts.as(owner))
                            .with(csrf))
                    .andExpect(status().isForbidden());
        }
        assertThat(catalogue()).isEqualTo(before);
        assertThat(count("SELECT COUNT(*) FROM import_batches")).isZero();
    }

    // ---------------------------------------------------------------- errors file

    @Test
    void errorsCsvListsEachErrorWithItsRowAndIsFormulaSafe() throws Exception {
        String csv = "SKU,Name,Dept,Price,=Note\n"
                + "NEW-1,\"=HYPERLINK(\"\"http://x\"\")\",Tops,abc,hello\n"
                + "TEE-1,Tee,Tops,25.00,\n"
                + "NEW-2,Fine,Tops,1.00,ok\n";
        String mapping = "{\"sku\": \"SKU\", \"name\": \"Name\", \"category\": \"Dept\", \"list_price\": \"Price\"}";
        send(mvc, "/api/imports/products/errors.csv", "mine.csv", csv, "mapping", mapping)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"products-errors.csv\""))
                .andExpect(content().string(
                        "line,SKU,Name,Dept,Price,'=Note,field,column,error\r\n"
                                + "2,NEW-1,\"'=HYPERLINK(\"\"http://x\"\")\",Tops,abc,hello,list_price,Price,"
                                + "'list_price' must be a price of 0 or more with at most 2 decimals.\r\n"
                                + "3,TEE-1,Tee,Tops,25.00,,sku,SKU,SKU 'TEE-1' already exists. Choose “Create and update” to change it.\r\n"));
        // Without errors: the header row only. A mapping problem has no line and no values.
        send(mvc, "/api/imports/products/errors.csv", "ok.csv", PRODUCTS + "NEW-3,Fine,Tops,1.00\n")
                .andExpect(content().string("line,sku,name,category,list_price,field,column,error\r\n"));
        send(mvc, "/api/imports/stores/errors.csv", "s.csv", "Code,Label\nA,B\n")
                .andExpect(content().string("line,Code,Label,field,column,error\r\n"
                        + ",,,name,,Required field 'name' (Store name) is not mapped: the file has no column named 'name'.\r\n"));
        // Never written, never recorded.
        assertThat(count("SELECT COUNT(*) FROM products WHERE sku LIKE 'NEW-%'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM import_batches")).isZero();
    }

    // ---------------------------------------------------------------- history

    @Test
    void historyListsEveryAttemptWithItsKindAndOutcome() throws Exception {
        long storesBatch = ((Number) JsonPath.read(body(importCsv("stores", STORES + "NEW,New one,\n")), "$.batchId")).longValue();
        importCsv("products", PRODUCTS + "TEE-1,Tee,Tops,25.00\n").andExpect(jsonPath("$.status").value("REJECTED"));
        validateCsv("stores", STORES + "DRY,Dry run,\n").andExpect(jsonPath("$.status").value("VALIDATED"));

        mvc.perform(get("/api/imports"))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].kind").value("products"))
                .andExpect(jsonPath("$.items[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.items[0].mode").value("create_only"))
                .andExpect(jsonPath("$.items[0].errorCount").value(1))
                .andExpect(jsonPath("$.items[0].created").value(0))
                .andExpect(jsonPath("$.items[0].importedBy").value("owner"))
                .andExpect(jsonPath("$.items[1].batchId").value(storesBatch))
                .andExpect(jsonPath("$.items[1].kind").value("stores"))
                .andExpect(jsonPath("$.items[1].status").value("IMPORTED"))
                .andExpect(jsonPath("$.items[1].created").value(1))
                .andExpect(jsonPath("$.items[1].updated").value(0))
                .andExpect(jsonPath("$.items[1].unchanged").value(0))
                .andExpect(jsonPath("$.items[1].errorCount").value(0))
                .andExpect(jsonPath("$.items[1].saleCount").value(0))
                .andExpect(jsonPath("$.items[1].totalAmount").value(0))
                .andExpect(jsonPath("$.items[1].fileName").value("stores.csv"));
        mvc.perform(get("/api/imports?kind=stores"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[*].kind", contains("stores")));
        mvc.perform(get("/api/imports?kind=sales")).andExpect(jsonPath("$.items", empty()));
        mvc.perform(get("/api/imports?kind=customers")).andExpect(status().isBadRequest());

        mvc.perform(get("/api/imports/" + storesBatch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("stores"))
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.importedBy").value("owner"))
                .andExpect(jsonPath("$.firstSoldAt").value(nullValue()))
                .andExpect(jsonPath("$.lastSoldAt").value(nullValue()));
        long rejectedBatch = count("SELECT id FROM import_batches WHERE status = 'REJECTED'");
        mvc.perform(get("/api/imports/" + rejectedBatch))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errorCount").value(1));
    }

    @Test
    void storesAndProductsFilesHaveAtMostFiveThousandRows() throws Exception {
        StringBuilder csv = new StringBuilder(PRODUCTS);
        for (int i = 1; i <= ImportService.MAX_CATALOG_ROWS; i++) {
            csv.append("P-").append(i).append(",Product ").append(i).append(",Misc,1.00\n");
        }
        validateCsv("products", csv.toString())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.created").value(5000));
        csv.append("P-5001,One too many,Misc,1.00\n");
        importCsv("products", csv.toString())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rowCount").value(5001))
                .andExpect(jsonPath("$.errors[0].line").value(nullValue()))
                .andExpect(jsonPath("$.errors[0].message").value("The file has more than 5,000 data rows; split it into smaller files."));
        assertThat(count("SELECT COUNT(*) FROM products WHERE sku LIKE 'P-%'")).isZero();
    }

    // ---------------------------------------------------------------- reporting freshness

    @Test
    void importedStoresAndProductsAppearInTheReportingContextAndReports() throws Exception {
        long version = dataVersion();
        importCsv("stores", STORES + "PVD,Providence,Providence\n").andExpect(jsonPath("$.status").value("IMPORTED"));
        assertThat(dataVersion()).isGreaterThan(version);
        mvc.perform(get("/api/dashboard/context"))
                .andExpect(jsonPath("$.stores[*].code", hasItem("PVD")));

        version = dataVersion();
        importCsv("products", PRODUCTS + "GIFT-1,Gift card,Gift cards,25.00\n").andExpect(jsonPath("$.status").value("IMPORTED"));
        assertThat(dataVersion()).isGreaterThan(version);
        String categories = body(mvc.perform(get("/api/reports/categories?" + PERIOD)));
        assertThat(JsonPath.<List<String>>read(categories, "$.rows[*].category")).contains("Gift cards");

        version = dataVersion();
        importCsv("sales", SALES + "PVD,G-1,2026-09-02T10:00:00Z,GIFT-1,2,25.00\n").andExpect(jsonPath("$.status").value("IMPORTED"));
        assertThat(dataVersion()).isGreaterThan(version);
        categories = body(mvc.perform(get("/api/reports/categories?" + PERIOD)));
        assertThat(JsonPath.<List<Double>>read(categories, "$.rows[?(@.category == 'Gift cards')].revenue")).containsExactly(50.0);
    }
}
