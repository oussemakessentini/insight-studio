package com.oussamaksantini.insightstudio.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.ApiInstance;
import com.oussamaksantini.insightstudio.testsupport.CubeStack;
import com.oussamaksantini.insightstudio.testsupport.HttpApiClient;
import com.oussamaksantini.insightstudio.testsupport.PdfText;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Cube report engine against <b>real</b> PostgreSQL 16, Cube Store and Cube v1.7.46 containers
 * ({@link CubeStack}: production mode, the repository's services/analytics model mounted read-only),
 * reconciled with the SQL engine and with hand-written SQL in this test (docs/cube-reports-contract.md
 * §7).
 *
 * <p>Two API instances run on the same database: {@code sql} ({@code insight.reports.engine=sql}) and
 * {@code cube}. Sessions live in PostgreSQL, so each owner signs in once and uses both. For every
 * business, window and store the Cube engine's JSON must be <b>byte for byte</b> the SQL engine's (so
 * are the CSV files and the PDF text), and both must match the independent SQL below, which buckets by
 * converting {@code sold_at} to the business's local date (not by instants like {@link SqlReportEngine}).
 *
 * <p>Data (seeded, plus explicit edge cases): four businesses in America/New_York, Europe/Paris,
 * Pacific/Auckland and Asia/Kolkata with sales just before and after local midnight, at month ends and
 * on both DST changes of 2026, products sold at several prices (including 0.00), receipts without
 * items, a store without sales, unsold categories whose names only differ in case (ties in the
 * order), a category with a comma; a business with a catalogue but no sales; a business with nothing.
 * Later tests import a CSV through the API, change data and the catalogue, use saved reports, stale
 * answers from a stub Cube, and finally stop Cube.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CubeReportsIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate DATA_FROM = LocalDate.parse("2025-11-01");
    private static final LocalDate DATA_TO = LocalDate.parse("2026-12-31");

    /** One business of the fixture, with its owner's session on both API instances. */
    record Biz(long id, String name, String zone, List<Long> stores, HttpApiClient sql, HttpApiClient cube) {
    }

    /** A report window; {@code null} dates mean the API's defaults. */
    record Window(String name, String from, String to) {

        String query(Long storeId) {
            List<String> params = new ArrayList<>();
            if (from != null) {
                params.add("from=" + from);
            }
            if (to != null) {
                params.add("to=" + to);
            }
            if (storeId != null) {
                params.add("storeId=" + storeId);
            }
            return params.isEmpty() ? "" : "?" + String.join("&", params);
        }
    }

    private static final List<Window> WINDOWS = List.of(
            new Window("all data", DATA_FROM.toString(), DATA_TO.toString()),
            new Window("API defaults", null, null),
            new Window("partial months around the spring DST changes", "2026-03-07", "2026-04-06"),
            new Window("month end and start", "2026-03-31", "2026-04-01"),
            new Window("New York DST day", "2026-03-08", "2026-03-08"),
            new Window("autumn DST changes", "2026-10-24", "2026-11-02"),
            new Window("Auckland New Year to winter", "2026-01-31", "2026-07-01"),
            new Window("no sales", "2025-01-01", "2025-03-31"));

    private CubeStack stack;
    private JdbcTemplate jdbc;
    private ApiInstance sqlApi;
    private ApiInstance cubeApi;
    private ApiInstance staleApi;
    private HttpServer staleCube;
    private final AtomicBoolean staleCubeRevalidated = new AtomicBoolean();
    private final AtomicInteger staleCubeCalls = new AtomicInteger();
    private final List<HttpApiClient> clients = new ArrayList<>();

    private Biz newYork;
    private Biz paris;
    private Biz auckland;
    private Biz kolkata;
    private Biz catalogueOnly;
    private Biz empty;
    private List<Biz> all;
    /** A member of New York (owner) and Paris (viewer). */
    private HttpApiClient memberOfBothCube;
    private HttpApiClient newYorkStale;
    private long newYorkTops;

    @BeforeAll
    void startStack() throws Exception {
        stack = new CubeStack();
        stack.startDatabase();
        // Flyway creates the schema (report_data_version included) before Cube looks at it.
        sqlApi = ApiInstance.start(stack.database(), Map.of("insight.reports.engine", "sql"));
        jdbc = sqlApi.bean(JdbcTemplate.class);
        Map<String, Long> businesses = seed();
        stack.startCube();
        cubeApi = ApiInstance.start(stack.database(), Map.of(
                "insight.reports.engine", "cube",
                "insight.cube.url", stack.cubeUrl(),
                "insight.cube.api-secret", stack.secret(),
                // Generous for Docker Desktop: the first query per time zone may build the rollups.
                "insight.reports.cube-timeout", "PT60S"));
        staleCube = startStaleCube();
        staleApi = ApiInstance.start(stack.database(), Map.of(
                "insight.reports.engine", "cube",
                "insight.cube.url", "http://127.0.0.1:" + staleCube.getAddress().getPort(),
                "insight.cube.api-secret", stack.secret(),
                "insight.reports.cube-timeout", "PT2S"));

        newYork = biz(businesses, "new-york", "America/New_York");
        paris = biz(businesses, "paris", "Europe/Paris");
        auckland = biz(businesses, "auckland", "Pacific/Auckland");
        kolkata = biz(businesses, "kolkata", "Asia/Kolkata");
        catalogueOnly = biz(businesses, "catalogue-only", "Europe/Paris");
        empty = biz(businesses, "empty", "UTC");
        all = List.of(newYork, paris, auckland, kolkata, catalogueOnly, empty);
        memberOfBothCube = signIn("both@example.com", cubeApi);
        newYorkStale = signIn("owner-new-york@example.com", staleApi);
        warmUp();
    }

    /**
     * Right after Cube starts, its first refresh pass builds every rollup in every zone; requests can
     * hang behind it for a minute (the API then answers 503 with Retry-After 30). Wait until each
     * business's reports answer before comparing.
     */
    private void warmUp() throws Exception {
        Instant giveUp = Instant.now().plusSeconds(300);
        for (Biz biz : all) {
            for (String kind : List.of("monthly", "categories")) {
                String path = "/api/reports/" + kind + WINDOWS.getFirst().query(null);
                while (biz.cube().get(path).statusCode() != 200) {
                    assertThat(Instant.now()).as("Cube answers within five minutes of starting").isBefore(giveUp);
                    Thread.sleep(2000);
                }
            }
        }
    }

    @AfterAll
    void stopStack() {
        clients.forEach(HttpApiClient::close);
        for (ApiInstance instance : new ApiInstance[] {staleApi, cubeApi, sqlApi}) {
            if (instance != null) {
                instance.close();
            }
        }
        if (staleCube != null) {
            staleCube.stop(0);
        }
        if (stack != null) {
            stack.close();
        }
    }

    // ---------------------------------------------------------------- reconciliation

    @Test
    @Order(1)
    void cubeAndSqlEnginesAgreeWithIndependentSqlForEveryBusinessWindowAndStore() throws Exception {
        SoftAssertions softly = new SoftAssertions();
        int compared = 0;
        for (Biz biz : all) {
            List<Long> stores = new ArrayList<>();
            stores.add(null);
            stores.addAll(biz.stores());
            for (Window window : WINDOWS) {
                for (Long store : stores) {
                    compared += reconcile(softly, biz, window, store);
                }
            }
        }
        softly.assertAll();
        assertThat(compared).isGreaterThan(150);
    }

    @Test
    @Order(2)
    void csvFilesAreByteForByteAndPdfsShowTheSameFigures() throws Exception {
        for (Biz biz : List.of(newYork, paris, catalogueOnly, empty)) {
            for (String kind : List.of("monthly", "categories")) {
                for (Window window : List.of(WINDOWS.get(0), WINDOWS.get(2), WINDOWS.getLast())) {
                    String path = "/api/reports/" + kind + ".csv" + window.query(null);
                    HttpResponse<byte[]> sqlCsv = biz.sql().getBytes(path);
                    HttpResponse<byte[]> cubeCsv = biz.cube().getBytes(path);
                    assertThat(cubeCsv.statusCode()).as(path).isEqualTo(200);
                    assertThat(cubeCsv.headers().firstValue("X-Report-Engine")).hasValue("cube");
                    assertThat(sqlCsv.headers().firstValue("X-Report-Engine")).hasValue("sql");
                    assertThat(cubeCsv.body()).as("%s %s", biz.name(), path).isEqualTo(sqlCsv.body());
                    assertThat(cubeCsv.headers().firstValue("Content-Disposition"))
                            .isEqualTo(sqlCsv.headers().firstValue("Content-Disposition"));

                    String pdfPath = "/api/reports/" + kind + ".pdf" + window.query(null);
                    HttpResponse<byte[]> sqlPdf = biz.sql().getBytes(pdfPath);
                    HttpResponse<byte[]> cubePdf = biz.cube().getBytes(pdfPath);
                    assertThat(cubePdf.statusCode()).isEqualTo(200);
                    assertThat(cubePdf.headers().firstValue("X-Report-Engine")).hasValue("cube");
                    // Identical apart from the "Generated <time>" footer.
                    assertThat(pdfText(cubePdf.body())).as("%s %s", biz.name(), pdfPath).isEqualTo(pdfText(sqlPdf.body()));
                }
            }
        }
    }

    // ---------------------------------------------------------------- freshness

    @Test
    @Order(3)
    void salesImportedThroughTheApiAreInTheNextReport() throws Exception {
        String csv = String.join("\n",
                "store_code,receipt_number,sold_at,sku,quantity,unit_price",
                "NY1,IMP-1,2026-12-31T23:59:00,NY-TOP,3,31.17",
                "NY1,IMP-1,2026-12-31T23:59:00,NY-COAT,1,199.99",
                "NY2,IMP-2,2026-12-01T00:00:01,NY-PANT,2,0.01",
                "NY2,IMP-3,2027-01-01T00:00:00,NY-PANT,1,50.00",
                "");
        String boundary = "cube-reports-boundary";
        HttpResponse<String> imported = newYork.cube().postMultipart("/api/imports?dryRun=false", boundary,
                HttpApiClient.multipartFile(boundary, "december.csv", csv.getBytes(StandardCharsets.UTF_8)));
        assertThat(imported.statusCode()).as(imported.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(imported.body(), "$.status")).isEqualTo("IMPORTED");

        // Immediately: the figures include the import, or the API says they are being updated.
        Window december = new Window("December", "2026-12-01", "2026-12-31");
        for (String kind : List.of("monthly", "categories")) {
            String body = freshReport(newYork.cube(), "/api/reports/" + kind + december.query(null));
            SoftAssertions softly = new SoftAssertions();
            checkAgainstIndependentSql(softly, newYork, december.from(), december.to(), null, kind, body);
            softly.assertAll();
            assertThat(body).isEqualTo(newYork.sql().get("/api/reports/" + kind + december.query(null)).body());
        }
        assertThat(totalRevenue(newYork.cube(), "/api/reports/monthly" + december.query(null)))
                .isEqualByComparingTo(expectedRevenue(newYork, december));
    }

    @Test
    @Order(4)
    void everyCommittedChangeIsInTheNextReport() throws Exception {
        Window window = new Window("all data", DATA_FROM.toString(), DATA_TO.toString());
        long store = newYork.stores().getFirst();
        SqlFixture db = new SqlFixture(jdbc);
        for (int i = 0; i < 5; i++) {
            db.sale(store, "LIVE-" + i, "2026-06-15T12:00:00Z", newYorkTops, 1, "%d.25".formatted(10 + i));
            for (String kind : List.of("monthly", "categories")) {
                String body = freshReport(newYork.cube(), "/api/reports/" + kind + window.query(null));
                SoftAssertions softly = new SoftAssertions();
                checkAgainstIndependentSql(softly, newYork, window.from(), window.to(), null, kind, body);
                softly.assertAll();
            }
        }
        // A deletion too (statement-level triggers bump the version for every kind of change).
        jdbc.update("DELETE FROM sales WHERE receipt_number = 'LIVE-4'");
        String body = freshReport(newYork.cube(), "/api/reports/categories" + window.query(null));
        SoftAssertions softly = new SoftAssertions();
        checkAgainstIndependentSql(softly, newYork, window.from(), window.to(), null, "categories", body);
        softly.assertAll();
    }

    @Test
    @Order(5)
    void aNewCatalogueCategoryShowsUpAtOnce() throws Exception {
        SqlFixture db = new SqlFixture(jdbc);
        long giftCard = db.product(paris.id(), "P-GIFT", "Gift card", "Gift cards", "25.00");
        Window window = new Window("all data", DATA_FROM.toString(), DATA_TO.toString());

        String body = freshReport(paris.cube(), "/api/reports/categories" + window.query(null));
        assertThat(categoryNames(body)).contains("Gift cards");
        assertThat(JsonPath.<List<Integer>>read(body, "$.rows[?(@.category == 'Gift cards')].orders")).containsExactly(0);
        assertThat(body).isEqualTo(paris.sql().get("/api/reports/categories" + window.query(null)).body());

        db.sale(paris.stores().getFirst(), "GIFT-1", "2026-07-14T10:00:00Z", giftCard, 4, "25.00");
        body = freshReport(paris.cube(), "/api/reports/categories" + window.query(null));
        assertThat(JsonPath.<List<Double>>read(body, "$.rows[?(@.category == 'Gift cards')].revenue")).containsExactly(100.0);
        assertThat(body).isEqualTo(paris.sql().get("/api/reports/categories" + window.query(null)).body());
    }

    // ---------------------------------------------------------------- isolation

    @Test
    @Order(6)
    void aBusinessOnlyEverSeesItsOwnFigures() throws Exception {
        Window window = WINDOWS.getFirst();
        // The member of New York and Paris gets each business's figures, chosen by membership.
        String asNewYork = memberOfBothCube.get("/api/reports/categories" + window.query(null),
                TestAccounts.BUSINESS_HEADER, String.valueOf(newYork.id())).body();
        String asParis = memberOfBothCube.get("/api/reports/categories" + window.query(null),
                TestAccounts.BUSINESS_HEADER, String.valueOf(paris.id())).body();
        assertThat(asNewYork).isEqualTo(newYork.cube().get("/api/reports/categories" + window.query(null)).body());
        assertThat(asParis).isEqualTo(paris.cube().get("/api/reports/categories" + window.query(null)).body());
        assertThat(categoryNames(asNewYork)).doesNotContainAnyElementsOf(categoryNames(asParis));

        // Another business cannot be selected, nor can its stores be used as a filter.
        HttpResponse<String> otherBusiness = newYork.cube().get("/api/reports/monthly",
                TestAccounts.BUSINESS_HEADER, String.valueOf(paris.id()));
        assertThat(otherBusiness.statusCode()).isEqualTo(404);
        HttpResponse<String> otherStore = newYork.cube().get("/api/reports/monthly?storeId=" + paris.stores().getFirst());
        assertThat(otherStore.statusCode()).isEqualTo(404);
        assertThat((String) JsonPath.read(otherStore.body(), "$.detail"))
                .isEqualTo("Store %d was not found.".formatted(paris.stores().getFirst()));
        assertThat(otherStore.headers().firstValue("X-Report-Engine")).hasValue("cube");

        // The empty business sees zeros, never another business's sales.
        String emptyReport = empty.cube().get("/api/reports/monthly" + window.query(null)).body();
        assertThat((Integer) JsonPath.read(emptyReport, "$.totals.orders")).isZero();
    }

    // ---------------------------------------------------------------- saved reports

    @Test
    @Order(7)
    void savedReportRunsAndExportsShowTheJsonTotals() throws Exception {
        long store = newYork.stores().getFirst();
        for (String kind : List.of("monthly", "categories")) {
            HttpResponse<String> created = newYork.cube().postJson("/api/saved-reports", """
                    {"name": "Cube %s", "kind": "%s", "range": {"type": "fixed", "from": "2026-02-15", "to": "2026-11-20"},
                     "storeId": %d}""".formatted(kind, kind, store));
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
            long id = ((Number) JsonPath.read(created.body(), "$.id")).longValue();

            HttpResponse<String> run = newYork.cube().get("/api/saved-reports/" + id + "/report");
            assertThat(run.statusCode()).isEqualTo(200);
            assertThat(run.headers().firstValue("X-Report-Engine")).hasValue("cube");
            String adhocPath = "/api/reports/" + kind + "?from=2026-02-15&to=2026-11-20&storeId=" + store;
            JsonNode report = JSON.readTree(run.body()).get(kind);
            assertThat(report).isEqualTo(JSON.readTree(newYork.cube().get(adhocPath).body()));
            assertThat(report).isEqualTo(JSON.readTree(newYork.sql().get(adhocPath).body()));
            JsonNode totals = report.get("totals");

            HttpResponse<byte[]> csv = newYork.cube().getBytes("/api/saved-reports/" + id + "/report.csv");
            assertThat(csv.statusCode()).isEqualTo(200);
            assertThat(csv.headers().firstValue("X-Report-Engine")).hasValue("cube");
            List<String[]> lines = new String(csv.body(), StandardCharsets.UTF_8).lines().skip(1)
                    .map(CubeReportsIntegrationTest::csvCells).toList();
            BigDecimal revenue = lines.stream().map(cells -> new BigDecimal(cells[1])).reduce(BigDecimal.ZERO, BigDecimal::add);
            long units = lines.stream().mapToLong(cells -> Long.parseLong(cells[kind.equals("monthly") ? 3 : 2])).sum();
            assertThat(revenue).isEqualByComparingTo(totals.get("revenue").decimalValue());
            assertThat(units).isEqualTo(totals.get("unitsSold").longValue());
            if (kind.equals("monthly")) {
                assertThat(lines.stream().mapToLong(cells -> Long.parseLong(cells[2])).sum())
                        .isEqualTo(totals.get("orders").longValue());
            }

            HttpResponse<byte[]> pdf = newYork.cube().getBytes("/api/saved-reports/" + id + "/report.pdf");
            assertThat(pdf.statusCode()).isEqualTo(200);
            assertThat(pdf.headers().firstValue("X-Report-Engine")).hasValue("cube");
            String text = PdfText.text(pdf.body());
            assertThat(text).contains(com.oussamaksantini.insightstudio.report.pdf.PdfFormats.money(
                    totals.get("revenue").decimalValue(), "USD"));
            assertThat(text).contains("Total");
        }
    }

    // ---------------------------------------------------------------- failures

    @Test
    @Order(8)
    void staleCubeAnswersAreA503AndNeverShown() throws Exception {
        long version = jdbc.queryForObject("SELECT version FROM report_data_version", Long.class);
        assertThat(version).as("the stub answers with version 7").isGreaterThan(7);

        for (String path : List.of("/api/reports/monthly", "/api/reports/categories", "/api/reports/monthly.csv",
                "/api/reports/categories.csv", "/api/reports/monthly.pdf", "/api/reports/categories.pdf")) {
            HttpResponse<byte[]> response = newYorkStale.getBytes(path + "?from=2026-03-20&to=2026-06-10");
            assertUnavailable(response, path, "Report figures are being updated after recent changes. Try again in a few seconds.", "5");
        }
        assertThat(staleCubeRevalidated).as("asked again with must-revalidate").isTrue();
        assertThat(staleCubeCalls.get()).isGreaterThan(6);
    }

    @Test
    @Order(9)
    void whenCubeIsDownEveryReportIsA503WithoutAFile() throws Exception {
        stack.stopCube();

        for (String path : List.of("/api/reports/monthly", "/api/reports/categories", "/api/reports/monthly.csv",
                "/api/reports/categories.csv", "/api/reports/monthly.pdf", "/api/reports/categories.pdf")) {
            HttpResponse<byte[]> response = newYork.cube().getBytes(path);
            assertUnavailable(response, path, "Report figures are temporarily unavailable. Try again in a minute.", "60");
        }
        String saved = newYork.cube().get("/api/saved-reports").body();
        long id = ((Number) JsonPath.<List<Object>>read(saved, "$[*].id").getFirst()).longValue();
        for (String suffix : List.of("/report", "/report.csv", "/report.pdf")) {
            assertUnavailable(newYork.cube().getBytes("/api/saved-reports/" + id + suffix), suffix,
                    "Report figures are temporarily unavailable. Try again in a minute.", "60");
        }
        // The SQL engine is unaffected; there is no fallback from one engine to the other.
        assertThat(newYork.sql().get("/api/reports/monthly").statusCode()).isEqualTo(200);
    }

    private static void assertUnavailable(HttpResponse<byte[]> response, String path, String detail, String retryAfter) {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        assertThat(response.statusCode()).as("%s: %s", path, body).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).as(path).hasValue(retryAfter);
        assertThat(response.headers().firstValue("Content-Type")).as(path).hasValue("application/problem+json");
        assertThat(response.headers().firstValue("Content-Disposition")).as("no file for %s", path).isEmpty();
        assertThat(response.headers().firstValue("X-Report-Engine")).as(path).hasValue("cube");
        assertThat((String) JsonPath.read(body, "$.detail")).isEqualTo(detail);
        assertThat(body).doesNotContain("Cube", "cube", "pre_aggregation", "data_version");
    }

    // ---------------------------------------------------------------- comparing reports

    /** Compares one business/window/store for both report kinds; returns the number of comparisons. */
    private int reconcile(SoftAssertions softly, Biz biz, Window window, Long store) throws Exception {
        int compared = 0;
        for (String kind : List.of("monthly", "categories")) {
            String path = "/api/reports/" + kind + window.query(store);
            HttpResponse<String> sql = biz.sql().get(path);
            HttpResponse<String> cube = biz.cube().get(path);
            String label = "%s, %s, store %s, %s".formatted(biz.name(), window.name(), store, kind);
            softly.assertThat(cube.statusCode()).as(label + ": " + cube.body()).isEqualTo(200);
            softly.assertThat(sql.statusCode()).as(label).isEqualTo(200);
            softly.assertThat(cube.headers().firstValue("X-Report-Engine")).as(label).hasValue("cube");
            softly.assertThat(sql.headers().firstValue("X-Report-Engine")).as(label).hasValue("sql");
            softly.assertThat(cube.body()).as(label + ": cube engine = sql engine").isEqualTo(sql.body());
            if (cube.statusCode() == 200) {
                String from = JsonPath.read(cube.body(), "$.period.from");
                String to = JsonPath.read(cube.body(), "$.period.to");
                checkAgainstIndependentSql(softly, biz, from, to, store, kind, cube.body());
            }
            compared++;
        }
        return compared;
    }

    /** Asserts a report's rows and totals against the hand-written SQL below. */
    private void checkAgainstIndependentSql(
            SoftAssertions softly, Biz biz, String from, String to, Long store, String kind, String body) {
        JsonNode report = JSON.readTree(body);
        String label = "%s %s..%s store %s %s".formatted(biz.name(), from, to, store, kind);
        if (kind.equals("monthly")) {
            Map<String, Totals> expected = expectedMonths(biz, from, to, store);
            Totals sum = Totals.ZERO;
            List<String> listed = new ArrayList<>();
            for (JsonNode row : report.get("rows")) {
                String month = row.get("month").asString();
                listed.add(month);
                Totals want = expected.getOrDefault(month, Totals.ZERO);
                softly.assertThat(Totals.of(row)).as("%s month %s", label, month).isEqualTo(want);
                sum = sum.plus(want);
            }
            softly.assertThat(report.get("rows").size()).as(label + " months").isEqualTo(monthsBetween(from, to));
            softly.assertThat(listed).as(label + " months with sales are listed").containsAll(expected.keySet());
            softly.assertThat(Totals.of(report.get("totals"))).as(label + " totals").isEqualTo(sum);
        } else {
            Map<String, Totals> expected = expectedCategories(biz, from, to, store);
            Map<String, Totals> actual = new LinkedHashMap<>();
            report.get("rows").forEach(row -> actual.put(row.get("category").asString(), Totals.of(row)));
            softly.assertThat(actual).as(label + " categories").isEqualTo(expected);
            BigDecimal previous = null;
            for (JsonNode row : report.get("rows")) {
                BigDecimal revenue = row.get("revenue").decimalValue();
                if (previous != null) {
                    softly.assertThat(revenue).as(label + " sorted by revenue").isLessThanOrEqualTo(previous);
                }
                previous = revenue;
            }
            Totals sum = expected.values().stream().reduce(Totals.ZERO, Totals::plus);
            Totals totals = Totals.of(report.get("totals"));
            softly.assertThat(totals.revenue()).as(label + " total revenue").isEqualByComparingTo(sum.revenue());
            softly.assertThat(totals.units()).as(label + " total units").isEqualTo(sum.units());
            softly.assertThat(totals.orders()).as(label + " distinct orders").isEqualTo(expectedOrders(biz, from, to, store));
        }
    }

    /** Revenue (scale 2), orders and units of a row or totals object. */
    record Totals(BigDecimal revenue, long orders, long units) {

        static final Totals ZERO = new Totals(new BigDecimal("0.00"), 0, 0);

        static Totals of(JsonNode node) {
            return new Totals(node.get("revenue").decimalValue().setScale(2, RoundingMode.HALF_UP),
                    node.get("orders").longValue(), node.get("unitsSold").longValue());
        }

        Totals plus(Totals other) {
            return new Totals(revenue.add(other.revenue), orders + other.orders, units + other.units);
        }
    }

    // ---------------------------------------------------------------- the independent SQL

    /** Local calendar date of a sale in the business's zone, for the hand-written queries. */
    private static final String LOCAL_DATE = "CAST(s.sold_at AT TIME ZONE b.time_zone AS date)";

    private Map<String, Totals> expectedMonths(Biz biz, String from, String to, Long store) {
        String sql = """
                SELECT to_char(date_trunc('month', %1$s), 'YYYY-MM-DD') AS month,
                       SUM(si.quantity * si.unit_price) AS revenue,
                       COUNT(DISTINCT s.id) AS orders,
                       SUM(si.quantity) AS units
                FROM businesses b
                JOIN stores st ON st.business_id = b.id
                JOIN sales s ON s.store_id = st.id
                JOIN sale_items si ON si.sale_id = s.id
                WHERE b.id = ? AND %1$s BETWEEN CAST(? AS date) AND CAST(? AS date) AND (CAST(? AS bigint) IS NULL OR st.id = ?)
                GROUP BY 1
                """.formatted(LOCAL_DATE);
        Map<String, Totals> months = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            months.put(rs.getString("month"), new Totals(rs.getBigDecimal("revenue").setScale(2, RoundingMode.HALF_UP),
                    rs.getLong("orders"), rs.getLong("units")));
        }, biz.id(), from, to, store, store);
        return months;
    }

    private Map<String, Totals> expectedCategories(Biz biz, String from, String to, Long store) {
        String sql = """
                SELECT p.category,
                       COALESCE(SUM(l.quantity * l.unit_price), 0) AS revenue,
                       COUNT(DISTINCT l.sale_id) AS orders,
                       COALESCE(SUM(l.quantity), 0) AS units
                FROM products p
                LEFT JOIN (
                    SELECT si.product_id, si.sale_id, si.quantity, si.unit_price
                    FROM businesses b
                    JOIN stores st ON st.business_id = b.id
                    JOIN sales s ON s.store_id = st.id
                    JOIN sale_items si ON si.sale_id = s.id
                    WHERE b.id = ? AND %1$s BETWEEN CAST(? AS date) AND CAST(? AS date)
                      AND (CAST(? AS bigint) IS NULL OR st.id = ?)
                ) l ON l.product_id = p.id
                WHERE p.business_id = ?
                GROUP BY p.category
                """.formatted(LOCAL_DATE);
        Map<String, Totals> categories = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            categories.put(rs.getString("category"), new Totals(rs.getBigDecimal("revenue").setScale(2, RoundingMode.HALF_UP),
                    rs.getLong("orders"), rs.getLong("units")));
        }, biz.id(), from, to, store, store, biz.id());
        return categories;
    }

    private long expectedOrders(Biz biz, String from, String to, Long store) {
        return jdbc.queryForObject("""
                SELECT COUNT(DISTINCT s.id)
                FROM businesses b
                JOIN stores st ON st.business_id = b.id
                JOIN sales s ON s.store_id = st.id
                WHERE b.id = ? AND %1$s BETWEEN CAST(? AS date) AND CAST(? AS date)
                  AND (CAST(? AS bigint) IS NULL OR st.id = ?)
                  AND EXISTS (SELECT 1 FROM sale_items si JOIN products p ON p.id = si.product_id
                              WHERE si.sale_id = s.id AND p.business_id = b.id)
                """.formatted(LOCAL_DATE), Long.class, biz.id(), from, to, store, store);
    }

    private BigDecimal expectedRevenue(Biz biz, Window window) {
        return expectedMonths(biz, window.from(), window.to(), null).values().stream()
                .map(Totals::revenue).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    private static int monthsBetween(String from, String to) {
        LocalDate first = LocalDate.parse(from).withDayOfMonth(1);
        LocalDate last = LocalDate.parse(to).withDayOfMonth(1);
        return (int) ChronoUnit.MONTHS.between(first, last) + 1;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Requests a Cube report right after a change: the API either answers with fresh figures (200) or
     * says they are being updated (503, Retry-After 5), never with old ones. Asks again after a 503,
     * for at most a minute.
     */
    private static String freshReport(HttpApiClient client, String path) throws Exception {
        Instant giveUp = Instant.now().plusSeconds(60);
        while (true) {
            HttpResponse<String> response = client.get(path);
            if (response.statusCode() == 200) {
                assertThat(response.headers().firstValue("X-Report-Engine")).hasValue("cube");
                return response.body();
            }
            assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
            assertThat(response.headers().firstValue("Retry-After")).hasValue("5");
            assertThat(Instant.now()).as("fresh figures within a minute").isBefore(giveUp);
            Thread.sleep(1000);
        }
    }

    private static BigDecimal totalRevenue(HttpApiClient client, String path) throws Exception {
        return JSON.readTree(client.get(path).body()).get("totals").get("revenue").decimalValue();
    }

    private static List<String> categoryNames(String body) {
        return JsonPath.read(body, "$.rows[*].category");
    }

    private static String pdfText(byte[] pdf) {
        return PdfText.text(pdf).lines().filter(line -> !line.contains("Generated")).collect(Collectors.joining("\n"));
    }

    /** CSV cells of a line without quoted commas in its first three cells. */
    private static String[] csvCells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells.toArray(String[]::new);
    }

    private Biz biz(Map<String, Long> businesses, String slug, String zone) throws Exception {
        long id = businesses.get(slug);
        String email = "owner-" + slug + "@example.com";
        List<Long> stores = jdbc.queryForList("SELECT id FROM stores WHERE business_id = ? ORDER BY id", Long.class, id);
        return new Biz(id, slug, zone, stores, signIn(email, sqlApi), signIn(email, cubeApi));
    }

    /** A session for {@code email} (signed in once per email, shared by every instance). */
    private final Map<String, Map<String, String>> sessions = new LinkedHashMap<>();

    private HttpApiClient signIn(String email, ApiInstance instance) throws Exception {
        HttpApiClient client = instance.client();
        clients.add(client);
        Map<String, String> cookies = sessions.get(email);
        if (cookies == null) {
            client.get("/api/session");
            HttpResponse<String> signedIn = client.postJson("/api/auth/sign-in",
                    "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, TestAccounts.PASSWORD));
            assertThat(signedIn.statusCode()).as(signedIn.body()).isEqualTo(200);
            sessions.put(email, client.cookies());
        } else {
            client.setCookies(cookies);
        }
        return client;
    }

    /** A Cube that always answers with captured results at data version 7 (and records the calls). */
    private HttpServer startStaleCube() throws IOException {
        String monthly = resource("/cube/monthly-rollup.json");
        String categories = resource("/cube/categories-rollup.json");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                staleCubeCalls.incrementAndGet();
                if (request.contains("\"cache\":\"must-revalidate\"")) {
                    staleCubeRevalidated.set(true);
                }
                byte[] bytes = (request.contains("order_categories") ? categories : monthly).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
        server.start();
        return server;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = CubeReportsIntegrationTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- data

    /** Seeds the businesses; returns their ids by slug. */
    private Map<String, Long> seed() {
        SqlFixture db = new SqlFixture(jdbc);
        TestAccounts accounts = new TestAccounts(jdbc);
        Map<String, Long> ids = new LinkedHashMap<>();
        Random random = new Random(20261001);

        long ny = db.business("New York Co", "new-york", "USD", "America/New_York");
        long ny1 = db.store(ny, "NY1", "Midtown", "New York");
        long ny2 = db.store(ny, "NY2", "Brooklyn", "New York");
        db.store(ny, "NY3", "Closed for works", "New York");
        newYorkTops = db.product(ny, "NY-TOP", "Tee", "Tops", "24.50");
        List<long[]> nyProducts = List.of(
                new long[] {newYorkTops, 2450},
                new long[] {db.product(ny, "NY-PANT", "Chinos", "Bottoms", "59.00"), 5900},
                new long[] {db.product(ny, "NY-COAT", "Parka", "Outerwear, Winter", "199.99"), 19999},
                new long[] {db.product(ny, "NY-SHOE", "Sneaker", "Footwear", "89.90"), 8990});
        db.product(ny, "NY-ACC", "Unsold belt", "accessories", "15.00");
        db.product(ny, "NY-BAG", "Unsold bag", "Bags", "45.00");
        db.product(ny, "NY-ZZ", "Unsold thing", "Zeta", "5.00");
        randomSales(db, random, "NY", List.of(ny1, ny2), nyProducts, 260);
        // Midnight, month ends and both DST changes (New York: 2026-03-08 and 2026-11-01).
        edgeSales(db, ny1, nyProducts, "NY-E",
                "2026-02-01T04:59:59Z", "2026-02-01T05:00:00Z", // Jan 31 23:59:59 / Feb 1 00:00 EST
                "2026-03-08T06:30:00Z", "2026-03-08T07:30:00Z", // 01:30 EST, 03:30 EDT
                "2026-04-01T03:59:30Z", "2026-04-01T04:00:30Z", // Mar 31 23:59:30 / Apr 1 00:00:30 EDT
                "2026-11-01T05:30:00Z", "2026-11-01T06:30:00Z", // 01:30 EDT, then 01:30 EST
                "2026-12-31T04:59:00Z", "2027-01-01T04:59:00Z"); // Dec 30 / Dec 31 23:59 EST
        db.sale(ny2, "NY-NOITEMS-1", "2026-03-15T15:00:00Z");
        db.sale(ny1, "NY-NOITEMS-2", "2026-04-01T04:30:00Z");
        ids.put("new-york", ny);

        long pa = db.business("Paris Co", "paris", "EUR", "Europe/Paris");
        long pa1 = db.store(pa, "PA1", "Marais", "Paris");
        long pa2 = db.store(pa, "PA2", "Lyon", "Lyon");
        List<long[]> paProducts = List.of(
                new long[] {db.product(pa, "PA-ROBE", "Robe", "Robes", "120.00"), 12000},
                new long[] {db.product(pa, "PA-TEE", "Tee", "Hauts", "29.90"), 2990},
                new long[] {db.product(pa, "PA-SAC", "Sac", "Maroquinerie", "249.00"), 24900});
        db.product(pa, "PA-FORM", "Formula", "=SUM(1;2)", "1.00");
        db.product(pa, "PA-EGAL", "Same name other case", "hauts d'été", "9.00");
        randomSales(db, random, "PA", List.of(pa1, pa2), paProducts, 240);
        edgeSales(db, pa2, paProducts, "PA-E",
                "2026-03-29T00:30:00Z", "2026-03-29T01:30:00Z", // 01:30 CET, 03:30 CEST
                "2026-03-31T21:59:00Z", "2026-03-31T22:00:00Z", // Mar 31 23:59 / Apr 1 00:00 CEST
                "2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z", // 02:30 CEST, then 02:30 CET
                "2026-12-31T22:59:59Z", "2026-12-31T23:00:00Z"); // Dec 31 23:59:59 / Jan 1 00:00 CET
        db.sale(pa1, "PA-NOITEMS", "2026-05-01T10:00:00Z");
        ids.put("paris", pa);

        long ak = db.business("Auckland Co", "auckland", "NZD", "Pacific/Auckland");
        long ak1 = db.store(ak, "AK1", "Ponsonby", "Auckland");
        List<long[]> akProducts = List.of(
                new long[] {db.product(ak, "AK-JUMP", "Jumper", "Knitwear", "149.00"), 14900},
                new long[] {db.product(ak, "AK-SOCK", "Socks", "Basics", "12.00"), 1200});
        randomSales(db, random, "AK", List.of(ak1), akProducts, 200);
        edgeSales(db, ak1, akProducts, "AK-E",
                "2026-01-31T10:30:00Z", "2026-01-31T11:30:00Z", // Jan 31 23:30 / Feb 1 00:30 NZDT
                "2026-04-04T13:30:00Z", "2026-04-04T14:30:00Z", // 02:30 NZDT, then 02:30 NZST
                "2026-06-30T11:59:00Z", "2026-06-30T12:30:00Z", // Jun 30 23:59 / Jul 1 00:30 NZST
                "2026-09-26T13:59:00Z", "2026-09-26T14:00:00Z"); // 01:59 NZST, 03:00 NZDT
        ids.put("auckland", ak);

        long ko = db.business("Kolkata Co", "kolkata", "INR", "Asia/Kolkata");
        long ko1 = db.store(ko, "KO1", "Park Street", "Kolkata");
        long ko2 = db.store(ko, "KO2", "Salt Lake", "Kolkata");
        List<long[]> koProducts = List.of(
                new long[] {db.product(ko, "KO-SARI", "Sari", "Saris", "4999.00"), 499900},
                new long[] {db.product(ko, "KO-KURTA", "Kurta", "Kurtas", "1299.50"), 129950});
        randomSales(db, random, "KO", List.of(ko1, ko2), koProducts, 200);
        edgeSales(db, ko2, koProducts, "KO-E",
                "2026-04-30T18:15:00Z", "2026-04-30T18:45:00Z", // Apr 30 23:45 / May 1 00:15 IST
                "2026-12-31T18:29:59Z", "2026-12-31T18:30:00Z"); // Dec 31 23:59:59 / Jan 1 00:00 IST
        ids.put("kolkata", ko);

        long catalogue = db.business("Catalogue Only", "catalogue-only", "EUR", "Europe/Paris");
        db.store(catalogue, "C1", "Not open yet", "Paris");
        db.product(catalogue, "C-1", "Planned", "Planned", "10.00");
        db.product(catalogue, "C-2", "Also planned", "also planned", "10.00");
        ids.put("catalogue-only", catalogue);

        long emptyBusiness = db.business("Empty Co", "empty", "USD", "UTC");
        ids.put("empty", emptyBusiness);

        for (Map.Entry<String, Long> entry : ids.entrySet()) {
            accounts.member("owner-" + entry.getKey() + "@example.com", entry.getValue(), Role.OWNER);
        }
        TestAccounts.TestUser both = accounts.member("both@example.com", ny, Role.OWNER);
        accounts.member(both, pa, Role.VIEWER);
        return ids;
    }

    /** {@code count} receipts at random instants with one to three products at changing prices. */
    private static void randomSales(SqlFixture db, Random random, String prefix, List<Long> stores, List<long[]> products, int count) {
        long start = Instant.parse("2025-10-31T00:00:00Z").getEpochSecond();
        long end = Instant.parse("2027-01-02T00:00:00Z").getEpochSecond();
        for (int i = 0; i < count; i++) {
            Instant soldAt = Instant.ofEpochSecond(start + (long) (random.nextDouble() * (end - start)));
            db.sale(stores.get(random.nextInt(stores.size())), prefix + "-" + i, soldAt.toString(), lines(random, products));
        }
    }

    private static void edgeSales(SqlFixture db, long store, List<long[]> products, String prefix, String... instants) {
        Random random = new Random(prefix.hashCode());
        for (int i = 0; i < instants.length; i++) {
            db.sale(store, prefix + "-" + i, instants[i], lines(random, products));
        }
    }

    /** (productId, quantity, unitPrice) triples: list price, a discount, or free. */
    private static Object[] lines(Random random, List<long[]> products) {
        List<long[]> shuffled = new ArrayList<>(products);
        java.util.Collections.shuffle(shuffled, random);
        int count = 1 + random.nextInt(Math.min(3, shuffled.size()));
        Object[] lines = new Object[count * 3];
        for (int i = 0; i < count; i++) {
            long[] product = shuffled.get(i);
            long cents = switch (random.nextInt(10)) {
                case 0 -> 0;
                case 1, 2 -> product[1] * 85 / 100;
                case 3 -> product[1] + 101;
                default -> product[1];
            };
            lines[i * 3] = product[0];
            lines[i * 3 + 1] = 1 + random.nextInt(5);
            lines[i * 3 + 2] = BigDecimal.valueOf(cents, 2).toPlainString();
        }
        return lines;
    }
}
