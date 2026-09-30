package com.oussamaksantini.insightstudio.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.business.BusinessRepository;
import com.oussamaksantini.insightstudio.importing.dto.ImportResult;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Import endpoints against PostgreSQL, called by an OWNER of "Test Co",
 * time zone America/New_York (UTC-4 in September).
 * <pre>
 * Test Co:  stores BOS, WEB; products TEE-1, JNS-1; existing receipt R-EXIST at BOS (Aug 31)
 * Other Co: store BOS; products TEE-1, OTH-1; existing receipt IMP-001 at its BOS
 *
 * imports/valid-sales.csv          receipt  lines                          units  total
 *   BOS IMP-001 09-01 14:30-04:00           TEE-1 2 x 24.50, JNS-1 1 x 98    3   147.00
 *   WEB IMP-002 09-02 09:15 (local = 13:15Z) TEE-1 3 x 25.00                 3    75.00
 *   BOS IMP-003 09-03 18:00Z                JNS-1 1 x 90.00                  1    90.00
 *                                           3 receipts, 4 lines             7   312.00
 * </pre>
 */
class ImportApiIntegrationTest extends PostgresIntegrationTest {

    private static final String HEADER = "store_code,receipt_number,sold_at,sku,quantity,unit_price\n";
    private static final String PERIOD = "from=2026-08-25&to=2026-09-05";

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ImportService importService;

    @Autowired
    BusinessRepository businesses;

    @LocalServerPort
    int port;

    long business;
    long bos;
    long web;
    long tee;
    long jeans;
    long otherBusiness;

    @BeforeEach
    void loadFixture() {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();
        business = db.business("Test Co", "test-co", "USD", "America/New_York");
        mvc = TestAccounts.ownerMvc(context, jdbc, business);
        bos = db.store(business, "BOS", "Back Bay", "Boston");
        web = db.store(business, "WEB", "Online", null);
        tee = db.product(business, "TEE-1", "Tee", "Tops", "25.00");
        jeans = db.product(business, "JNS-1", "Jeans", "Bottoms", "98.00");
        db.sale(bos, "R-EXIST", "2026-08-31T15:00:00Z", tee, 1, "25.00");

        otherBusiness = db.business("Other Co", "other-co", "EUR", "UTC");
        long otherStore = db.store(otherBusiness, "BOS", "Other Boston", null);
        long otherTee = db.product(otherBusiness, "TEE-1", "Other tee", "Tops", "10.00");
        db.product(otherBusiness, "OTH-1", "Other only", "Misc", "10.00");
        db.sale(otherStore, "IMP-001", "2026-09-01T10:00:00Z", otherTee, 1, "10.00");
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = ImportApiIntegrationTest.class.getResourceAsStream("/imports/" + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }

    private ResultActions upload(String fileName, byte[] content, Boolean dryRun) throws Exception {
        var request = multipart("/api/imports").file(new MockMultipartFile("file", fileName, "text/csv", content));
        if (dryRun != null) {
            request.param("dryRun", dryRun.toString());
        }
        return mvc.perform(request);
    }

    private ResultActions upload(String fileName, String content, Boolean dryRun) throws Exception {
        return upload(fileName, content.getBytes(StandardCharsets.UTF_8), dryRun);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private Map<String, Long> tableCounts() {
        return Map.of("sales", count("sales"), "sale_items", count("sale_items"), "import_batches", count("import_batches"));
    }

    private record Totals(BigDecimal revenue, long orders, long units, long salesListed) {
    }

    private Totals totals() throws Exception {
        String summary = mvc.perform(get("/api/dashboard/summary?" + PERIOD)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sales = mvc.perform(get("/api/sales?" + PERIOD)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Totals(
                new BigDecimal(JsonPath.read(summary, "$.revenue.value").toString()),
                new BigDecimal(JsonPath.read(summary, "$.orders.value").toString()).longValueExact(),
                new BigDecimal(JsonPath.read(summary, "$.unitsSold.value").toString()).longValueExact(),
                ((Number) JsonPath.read(sales, "$.totalItems")).longValue());
    }

    private long importValid() throws Exception {
        String body = upload("valid-sales.csv", resource("valid-sales.csv"), false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.batchId")).longValue();
    }

    // ---------------------------------------------------------------- validation and import

    @Test
    void dryRunIsTheDefaultAndWritesNothing() throws Exception {
        Map<String, Long> before = tableCounts();

        upload("valid-sales.csv", resource("valid-sales.csv"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.batchId").value(nullValue()))
                .andExpect(jsonPath("$.fileName").value("valid-sales.csv"))
                .andExpect(jsonPath("$.rowCount").value(4))
                .andExpect(jsonPath("$.saleCount").value(3))
                .andExpect(jsonPath("$.lineCount").value(4))
                .andExpect(jsonPath("$.totalAmount").value(312.00))
                .andExpect(jsonPath("$.errors", empty()))
                .andExpect(jsonPath("$.errorCount").value(0));
        upload("valid-sales.csv", resource("valid-sales.csv"), true)
                .andExpect(jsonPath("$.status").value("VALIDATED"));

        assertThat(tableCounts()).isEqualTo(before);
    }

    @Test
    void importWritesBatchSalesAndItems() throws Exception {
        byte[] file = resource("valid-sales.csv");
        String body = upload("valid-sales.csv", file, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.saleCount").value(3))
                .andExpect(jsonPath("$.lineCount").value(4))
                .andExpect(jsonPath("$.totalAmount").value(312.00))
                .andReturn().getResponse().getContentAsString();
        long batchId = ((Number) JsonPath.read(body, "$.batchId")).longValue();

        Map<String, Object> batch = jdbc.queryForMap("SELECT * FROM import_batches WHERE id = ?", batchId);
        assertThat(batch).containsEntry("business_id", business)
                .containsEntry("file_name", "valid-sales.csv")
                .containsEntry("content_sha256", ImportService.sha256(file))
                .containsEntry("status", "IMPORTED")
                .containsEntry("row_count", 4)
                .containsEntry("sale_count", 3)
                .containsEntry("line_count", 4);
        assertThat((BigDecimal) batch.get("total_amount")).isEqualByComparingTo("312.00");

        List<Map<String, Object>> sales = jdbc.queryForList("""
                SELECT s.store_id, s.receipt_number, s.sold_at, COUNT(si.id) AS lines, SUM(si.quantity) AS units,
                       SUM(si.quantity * si.unit_price) AS total
                FROM sales s JOIN sale_items si ON si.sale_id = s.id
                WHERE s.import_batch_id = ?
                GROUP BY s.id ORDER BY s.receipt_number
                """, batchId);
        assertThat(sales).extracting(r -> r.get("store_id"), r -> r.get("receipt_number"), r -> r.get("lines"), r -> r.get("units"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(bos, "IMP-001", 2L, 3L),
                        org.assertj.core.groups.Tuple.tuple(web, "IMP-002", 1L, 3L),
                        org.assertj.core.groups.Tuple.tuple(bos, "IMP-003", 1L, 1L));
        assertThat(sales).extracting(r -> ((BigDecimal) r.get("total")).toPlainString())
                .containsExactly("147.00", "75.00", "90.00");
        // Offsets are kept; the local time on IMP-002 is 09:15 New York time (EDT, UTC-4).
        assertThat(jdbc.queryForList("SELECT sold_at FROM sales WHERE import_batch_id = ? ORDER BY receipt_number",
                OffsetDateTime.class, batchId))
                .extracting(OffsetDateTime::toInstant)
                .containsExactly(
                        OffsetDateTime.parse("2026-09-01T18:30:00Z").toInstant(),
                        OffsetDateTime.parse("2026-09-02T13:15:00Z").toInstant(),
                        OffsetDateTime.parse("2026-09-03T18:00:00Z").toInstant());
        assertThat(jdbc.queryForObject("SELECT unit_price FROM sale_items si JOIN sales s ON s.id = si.sale_id "
                + "WHERE s.receipt_number = 'IMP-001' AND si.product_id = ?", BigDecimal.class, tee))
                .isEqualByComparingTo("24.50");
        // The existing receipt is untouched and not part of the batch.
        assertThat(jdbc.queryForObject("SELECT import_batch_id FROM sales WHERE receipt_number = 'R-EXIST'", Long.class)).isNull();
    }

    @Test
    void importMovesDashboardAndSalesByExactlyTheImportedTotals() throws Exception {
        Totals before = totals();
        importValid();
        Totals after = totals();

        assertThat(after.revenue().subtract(before.revenue())).isEqualByComparingTo("312.00");
        assertThat(after.orders() - before.orders()).isEqualTo(3);
        assertThat(after.units() - before.units()).isEqualTo(7);
        assertThat(after.salesListed() - before.salesListed()).isEqualTo(3);
    }

    @Test
    void rejectedFileReportsEveryErrorAndWritesNothing() throws Exception {
        Map<String, Long> before = tableCounts();

        upload("with-errors.csv", resource("with-errors.csv"), false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.batchId").value(nullValue()))
                .andExpect(jsonPath("$.rowCount").value(5))
                .andExpect(jsonPath("$.errorCount").value(6))
                .andExpect(jsonPath("$.errors[*].line").value(org.hamcrest.Matchers.contains(3, 4, 5, 5, 5, 6)))
                .andExpect(jsonPath("$.errors[*].column").value(org.hamcrest.Matchers.contains(
                        "sold_at", "store_code", "sku", "quantity", "unit_price", "receipt_number")))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("line 2")))
                .andExpect(jsonPath("$.errors[1].message").value("Unknown store code 'PVD'."))
                // OTH-1 exists, but in another business.
                .andExpect(jsonPath("$.errors[2].message").value("Unknown SKU 'OTH-1'."))
                .andExpect(jsonPath("$.errors[5].message").value(containsString("R-EXIST already exists")));

        assertThat(tableCounts()).isEqualTo(before);
    }

    @Test
    void receiptsOfAnotherBusinessDoNotCount() throws Exception {
        // Other Co has a receipt IMP-001 at its own BOS store; ours is a different store.
        upload("valid-sales.csv", resource("valid-sales.csv"), true).andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    @Test
    void duplicateReceiptWithinTheFile() throws Exception {
        String csv = HEADER
                + "BOS,D-1,2026-09-01T10:00:00Z,TEE-1,1,25.00\n"
                + "BOS,D-1,2026-09-01T10:00:00Z,TEE-1,2,25.00\n";
        upload("dup.csv", csv, false)
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].line").value(3))
                .andExpect(jsonPath("$.errors[0].column").value("sku"));
        assertThat(count("import_batches")).isZero();
    }

    @Test
    void sameFileCannotBeImportedTwiceIntoTheSameBusiness() throws Exception {
        importValid();
        long sales = count("sales");

        for (boolean dryRun : new boolean[] {true, false}) {
            upload("renamed.csv", resource("valid-sales.csv"), dryRun)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"))
                    .andExpect(jsonPath("$.errorCount").value(1))
                    .andExpect(jsonPath("$.errors", hasSize(1)))
                    .andExpect(jsonPath("$.errors[0].line").value(nullValue()))
                    .andExpect(jsonPath("$.errors[0].column").value(nullValue()))
                    .andExpect(jsonPath("$.errors[0].message").value(containsString("already been imported")));
        }
        assertThat(count("sales")).isEqualTo(sales);
        assertThat(count("import_batches")).isEqualTo(1);
    }

    @Test
    void sameFileMayBeImportedIntoAnotherBusiness() throws Exception {
        byte[] csv = (HEADER + "BOS,SHARED-1,2026-09-01T10:00:00Z,TEE-1,1,10.00\n").getBytes(StandardCharsets.UTF_8);
        upload("shared.csv", csv, false).andExpect(jsonPath("$.status").value("IMPORTED"));

        Business other = businesses.findBySlug("other-co").orElseThrow();
        ImportResult result = importService.importFile(other, "shared.csv", csv, false);
        assertThat(result.status()).isEqualTo(ImportStatus.IMPORTED);
        assertThat(jdbc.queryForObject("SELECT business_id FROM import_batches WHERE id = ?", Long.class, result.batchId()))
                .isEqualTo(otherBusiness);
        assertThat(jdbc.queryForObject("""
                SELECT st.business_id FROM sales s JOIN stores st ON st.id = s.store_id WHERE s.import_batch_id = ?
                """, Long.class, result.batchId())).isEqualTo(otherBusiness);

        assertThat(importService.importFile(other, "shared.csv", csv, true).status()).isEqualTo(ImportStatus.REJECTED);
    }

    @Test
    void listsAtMostOneHundredErrorsButCountsAll() throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 150; i++) {
            csv.append("BOS,E-").append(i).append(",2026-09-01T10:00:00Z,NOPE,1,1.00\n");
        }
        upload("many-errors.csv", csv.toString(), true)
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errorCount").value(150))
                .andExpect(jsonPath("$.errors", hasSize(100)))
                .andExpect(jsonPath("$.errors[0].line").value(2))
                .andExpect(jsonPath("$.errors[99].line").value(101));
    }

    @Test
    void rejectsMoreThanFiftyThousandRows() throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= ImportService.MAX_ROWS + 1; i++) {
            csv.append("BOS,M-").append(i).append(",2026-09-01T10:00:00Z,TEE-1,1,1.00\n");
        }
        upload("big.csv", csv.toString(), false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].line").value(ImportService.MAX_ROWS + 2))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("more than 50,000")));
        assertThat(count("import_batches")).isZero();
    }

    @Test
    void acceptsExactlyFiftyThousandRows() throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= ImportService.MAX_ROWS; i++) {
            csv.append("WEB,B-").append(i).append(",2026-09-01T10:00:00Z,TEE-1,1,1.00\n");
        }
        // Dry run: the limit is about validation; writing 50,000 receipts would only slow the suite down.
        upload("fifty-thousand.csv", csv.toString(), true)
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.saleCount").value(ImportService.MAX_ROWS))
                .andExpect(jsonPath("$.totalAmount").value(50000.00));
    }

    @Test
    void importsMoreRowsThanOneInsertStatementHolds() throws Exception {
        // Inserts are chunked by 10,000 rows; 10,001 receipts cross a chunk boundary.
        int receipts = 10_001;
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= receipts; i++) {
            csv.append("WEB,C-").append(i).append(",2026-09-01T10:00:00Z,TEE-1,1,1.00\n");
        }
        csv.append("WEB,C-1,2026-09-01T10:00:00Z,JNS-1,2,3.00\n");
        upload("chunks.csv", csv.toString(), false)
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.saleCount").value(receipts))
                .andExpect(jsonPath("$.lineCount").value(receipts + 1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales WHERE import_batch_id IS NOT NULL", Long.class))
                .isEqualTo(receipts);
        assertThat(jdbc.queryForObject("""
                SELECT SUM(si.quantity * si.unit_price) FROM sale_items si JOIN sales s ON s.id = si.sale_id
                WHERE s.import_batch_id IS NOT NULL
                """, BigDecimal.class)).isEqualByComparingTo("10007.00");
    }

    @Test
    void failureDuringTheWriteRollsEverythingBack() throws Exception {
        // Make the last line item insert fail inside the import transaction.
        jdbc.execute("""
                CREATE FUNCTION test_fail_on_price() RETURNS trigger AS $$
                BEGIN
                  IF NEW.unit_price = 90.00 THEN RAISE EXCEPTION 'simulated failure'; END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql
                """);
        jdbc.execute("CREATE TRIGGER test_fail BEFORE INSERT ON sale_items FOR EACH ROW EXECUTE FUNCTION test_fail_on_price()");
        try {
            Map<String, Long> before = tableCounts();
            upload("valid-sales.csv", resource("valid-sales.csv"), false).andExpect(status().isInternalServerError());
            assertThat(tableCounts()).isEqualTo(before);
        } finally {
            jdbc.execute("DROP TRIGGER test_fail ON sale_items");
            jdbc.execute("DROP FUNCTION test_fail_on_price()");
        }
        // Nothing was stored, so the same file still imports.
        importValid();
    }

    // ---------------------------------------------------------------- unreadable uploads

    @Test
    void unreadableUploadsAreBadRequests() throws Exception {
        upload("empty.csv", new byte[0], true)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("The file is empty."));
        upload("no-header.csv", "BOS,R-1,2026-09-01T10:00:00Z,TEE-1,1,1.00\n", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("header")));
        upload("sales.xlsx", HEADER + "BOS,R-1,2026-09-01T10:00:00Z,TEE-1,1,1.00\n", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString(".csv")));
        upload("latin1.csv", (HEADER + "BOS,R-1,2026-09-01T10:00:00Z,TEE-1,1,1.00\n").replace("R-1", "R-é")
                .getBytes(StandardCharsets.ISO_8859_1), true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("UTF-8")));
        mvc.perform(multipart("/api/imports").param("dryRun", "true"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/imports")
                        .file(new MockMultipartFile("file", "x.csv", "text/csv", "a".getBytes(StandardCharsets.UTF_8)))
                        .param("dryRun", "maybe"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filesOverTheUploadLimitAre413() throws Exception {
        // Size limits are enforced when the servlet container parses the multipart request, so this
        // goes through the real server rather than MockMvc.
        String boundary = "----import-test-boundary";
        byte[] big = new byte[5 * 1024 * 1024 + 10_000];
        java.util.Arrays.fill(big, (byte) 'a');
        byte[] body = HttpApiClient.multipartFile(boundary, "big.csv", big);

        try (HttpApiClient client = new HttpApiClient(port)) {
            // A real signed-in session: the limit applies after authentication and CSRF checks.
            client.get("/api/session");
            HttpResponse<String> signIn = client.postJson("/api/auth/sign-in", """
                    {"email": "owner-%d@example.com", "password": "%s"}
                    """.formatted(business, TestAccounts.PASSWORD));
            assertThat(signIn.statusCode()).as(signIn.body()).isEqualTo(200);
            HttpResponse<String> response = client.postMultipart("/api/imports?dryRun=false", boundary, body,
                    TestAccounts.BUSINESS_HEADER, Long.toString(business));
            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(response.body()).contains("\"status\":413");
        }
        assertThat(count("import_batches")).isZero();
    }

    // ---------------------------------------------------------------- history

    @Test
    void listsBatchesNewestFirstWithPaging() throws Exception {
        mvc.perform(get("/api/imports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.items", empty()));

        long first = importValid();
        String second = HEADER + "WEB,LATER-1,2026-09-04T12:00:00Z,JNS-1,2,50.00\n";
        String body = upload("second.csv", second, false).andReturn().getResponse().getContentAsString();
        long secondId = ((Number) JsonPath.read(body, "$.batchId")).longValue();
        // Other businesses' batches are never listed.
        importService.importFile(businesses.findBySlug("other-co").orElseThrow(), "other.csv",
                (HEADER + "BOS,O-1,2026-09-01T10:00:00Z,TEE-1,1,1.00\n").getBytes(StandardCharsets.UTF_8), false);

        mvc.perform(get("/api/imports"))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[*].batchId").value(org.hamcrest.Matchers.contains((int) secondId, (int) first)))
                .andExpect(jsonPath("$.items[0].fileName").value("second.csv"))
                .andExpect(jsonPath("$.items[0].rowCount").value(1))
                .andExpect(jsonPath("$.items[0].saleCount").value(1))
                .andExpect(jsonPath("$.items[0].lineCount").value(1))
                .andExpect(jsonPath("$.items[0].totalAmount").value(100.00))
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty());

        mvc.perform(get("/api/imports?page=1&size=1"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[*].batchId").value(org.hamcrest.Matchers.contains((int) first)));
        mvc.perform(get("/api/imports?page=5")).andExpect(jsonPath("$.items", empty()));

        mvc.perform(get("/api/imports?size=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/imports?size=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/imports?page=-1")).andExpect(status().isBadRequest());
    }

    @Test
    void showsOneBatch() throws Exception {
        long batchId = importValid();
        mvc.perform(get("/api/imports/" + batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.fileName").value("valid-sales.csv"))
                .andExpect(jsonPath("$.rowCount").value(4))
                .andExpect(jsonPath("$.saleCount").value(3))
                .andExpect(jsonPath("$.lineCount").value(4))
                .andExpect(jsonPath("$.totalAmount").value(312.00))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.firstSoldAt").value("2026-09-01T18:30:00Z"))
                .andExpect(jsonPath("$.lastSoldAt").value("2026-09-03T18:00:00Z"));
    }

    @Test
    void unknownOrForeignBatchIsNotFound() throws Exception {
        mvc.perform(get("/api/imports/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Import 999 was not found."));

        ImportResult foreign = importService.importFile(businesses.findBySlug("other-co").orElseThrow(), "other.csv",
                (HEADER + "BOS,O-1,2026-09-01T10:00:00Z,TEE-1,1,1.00\n").getBytes(StandardCharsets.UTF_8), false);
        mvc.perform(get("/api/imports/" + foreign.batchId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Import %d was not found.".formatted(foreign.batchId())));

        mvc.perform(get("/api/imports/0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/imports/abc")).andExpect(status().isBadRequest());
    }
}
