package com.oussamaksantini.insightstudio.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * Store endpoints against PostgreSQL with a hand-built dataset (business time zone UTC).
 * Reporting window 2026-06-01..2026-06-14 (starts on a Monday); previous period 2026-05-18..2026-05-31.
 * <pre>
 * Store          receipts in window                                       revenue orders units  previous
 * Alpha (Paris)  S1 06-01 Tee x2 @ 20 + Jacket x1 @ 100 = 140               380.00      3     7    20.00 (S0)
 *                S3 06-08 Jacket x1 @ 120, S4 06-09 Scarf x3 @ 40,
 *                E2 06-04 receipt without items (not an order)
 * Bravo (Lyon)   S2 06-02 Tee x1 @ 25                                        25.00      1     1    50.00 (P1)
 * Charlie (-)    E1 06-03 receipt without items only                           0.00      0     0     0.00
 * Total                                                                     405.00      4     8
 * Alpha categories: Outerwear 220 (2 units, 2 orders), Accessories 120 (3, 1), Tops 40 (2, 1).
 * Another business has store X with a sale in the window.
 * </pre>
 */
class StoreApiIntegrationTest extends PostgresIntegrationTest {

    private static final String WINDOW = "from=2026-06-01&to=2026-06-14";

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    long alpha;
    long bravo;
    long charlie;
    long otherStore;

    @BeforeEach
    void loadFixture() {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();

        long business = db.business("Test Co", "test-co", "EUR", "UTC");

        mvc = TestAccounts.ownerMvc(context, jdbc, business);
        alpha = db.store(business, "A", "Alpha", "Paris");
        bravo = db.store(business, "B", "Bravo", "Lyon");
        charlie = db.store(business, "C", "Charlie", null);
        long tee = db.product(business, "TEE-001", "Classic Tee", "Tops", "25.00");
        long jacket = db.product(business, "JKT-001", "Denim Jacket", "Outerwear", "120.00");
        long scarf = db.product(business, "SCF-001", "Wool Scarf", "Accessories", "40.00");

        db.sale(bravo, "P1", "2026-05-20T10:00:00Z", tee, 2, "25.00");
        db.sale(alpha, "S0", "2026-05-25T10:00:00Z", tee, 1, "20.00");
        db.sale(alpha, "S1", "2026-06-01T10:00:00Z", tee, 2, "20.00", jacket, 1, "100.00");
        db.sale(bravo, "S2", "2026-06-02T10:00:00Z", tee, 1, "25.00");
        db.sale(charlie, "E1", "2026-06-03T10:00:00Z");
        db.sale(alpha, "E2", "2026-06-04T10:00:00Z");
        db.sale(alpha, "S3", "2026-06-08T10:00:00Z", jacket, 1, "120.00");
        db.sale(alpha, "S4", "2026-06-09T10:00:00Z", scarf, 3, "40.00");

        long other = db.business("Other Co", "other-co", "USD", "UTC");
        otherStore = db.store(other, "X", "Other", null);
        long otherProduct = db.product(other, "TEE-001", "Classic Tee", "Tops", "99.00");
        db.sale(otherStore, "X-1", "2026-06-01T12:00:00Z", otherProduct, 5, "99.00");
    }

    @Nested
    class StoreList {

        @Test
        void listsEveryStoreIncludingThoseWithoutOrders() throws Exception {
            mvc.perform(get("/api/stores?" + WINDOW))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.period.from").value("2026-06-01"))
                    .andExpect(jsonPath("$.period.to").value("2026-06-14"))
                    .andExpect(jsonPath("$.previousPeriod.from").value("2026-05-18"))
                    .andExpect(jsonPath("$.previousPeriod.to").value("2026-05-31"))
                    .andExpect(jsonPath("$.stores[*].name", contains("Alpha", "Bravo", "Charlie")))
                    .andExpect(jsonPath("$.stores[0].storeId").value(alpha))
                    .andExpect(jsonPath("$.stores[0].code").value("A"))
                    .andExpect(jsonPath("$.stores[0].city").value("Paris"))
                    .andExpect(jsonPath("$.stores[0].revenue").value(380.00))
                    .andExpect(jsonPath("$.stores[0].orders").value(3))
                    .andExpect(jsonPath("$.stores[0].unitsSold").value(7))
                    .andExpect(jsonPath("$.stores[0].averageOrderValue").value(126.67))
                    .andExpect(jsonPath("$.stores[0].revenueSharePercent").value(93.8))
                    .andExpect(jsonPath("$.stores[0].revenueChangePercent").value(1800.0))
                    .andExpect(jsonPath("$.stores[1].revenue").value(25.00))
                    .andExpect(jsonPath("$.stores[1].orders").value(1))
                    .andExpect(jsonPath("$.stores[1].revenueSharePercent").value(6.2))
                    .andExpect(jsonPath("$.stores[1].revenueChangePercent").value(-50.0))
                    .andExpect(jsonPath("$.stores[2].city", nullValue()))
                    .andExpect(jsonPath("$.stores[2].revenue").value(0))
                    .andExpect(jsonPath("$.stores[2].orders").value(0))
                    .andExpect(jsonPath("$.stores[2].unitsSold").value(0))
                    .andExpect(jsonPath("$.stores[2].averageOrderValue").value(0))
                    .andExpect(jsonPath("$.stores[2].revenueSharePercent").value(0))
                    .andExpect(jsonPath("$.stores[2].revenueChangePercent", nullValue()));
        }

        @Test
        void ignoresTheGlobalStoreFilter() throws Exception {
            mvc.perform(get("/api/stores?" + WINDOW + "&storeId=" + bravo))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stores", hasSize(3)));
        }

        @Test
        void breaksRevenueTiesByName() throws Exception {
            // Nothing sold in this window: every store has zero revenue.
            mvc.perform(get("/api/stores?from=2026-07-01&to=2026-07-07"))
                    .andExpect(jsonPath("$.stores[*].name", contains("Alpha", "Bravo", "Charlie")))
                    .andExpect(jsonPath("$.stores[0].revenueSharePercent").value(0));
        }

        @Test
        void totalsEqualTheDashboardSummary() throws Exception {
            String stores = body("/api/stores?" + WINDOW);
            String summary = body("/api/dashboard/summary?" + WINDOW);

            assertThat(sum(stores, "$.stores[*].revenue")).isEqualByComparingTo(decimal(summary, "$.revenue.value"));
            assertThat(sum(stores, "$.stores[*].orders")).isEqualByComparingTo(decimal(summary, "$.orders.value"));
            assertThat(sum(stores, "$.stores[*].unitsSold")).isEqualByComparingTo(decimal(summary, "$.unitsSold.value"));
            assertThat(decimal(summary, "$.revenue.value")).isEqualByComparingTo("405.00");
            assertThat(decimal(summary, "$.orders.value")).isEqualByComparingTo("4");
        }

        @Test
        void rejectsAnInvalidRange() throws Exception {
            mvc.perform(get("/api/stores?from=2026-06-05&to=2026-06-01"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("'from' (2026-06-05) must be on or before 'to' (2026-06-01)."));
            mvc.perform(get("/api/stores?from=06/01/2026"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Invalid value '06/01/2026' for parameter 'from'."));
        }
    }

    @Nested
    class StoreDetail {

        @Test
        void comparesWithThePreviousPeriod() throws Exception {
            mvc.perform(get("/api/stores/" + alpha + "?" + WINDOW))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.store.id").value(alpha))
                    .andExpect(jsonPath("$.store.code").value("A"))
                    .andExpect(jsonPath("$.store.name").value("Alpha"))
                    .andExpect(jsonPath("$.store.city").value("Paris"))
                    .andExpect(jsonPath("$.period.from").value("2026-06-01"))
                    .andExpect(jsonPath("$.previousPeriod.from").value("2026-05-18"))
                    .andExpect(jsonPath("$.previousPeriod.to").value("2026-05-31"))
                    .andExpect(jsonPath("$.revenue.value").value(380.00))
                    .andExpect(jsonPath("$.revenue.previousValue").value(20.00))
                    .andExpect(jsonPath("$.revenue.changePercent").value(1800.0))
                    .andExpect(jsonPath("$.orders.value").value(3))
                    .andExpect(jsonPath("$.orders.previousValue").value(1))
                    .andExpect(jsonPath("$.orders.changePercent").value(200.0))
                    .andExpect(jsonPath("$.unitsSold.value").value(7))
                    .andExpect(jsonPath("$.unitsSold.previousValue").value(1))
                    .andExpect(jsonPath("$.averageOrderValue.value").value(126.67))
                    .andExpect(jsonPath("$.averageOrderValue.previousValue").value(20.00))
                    .andExpect(jsonPath("$.averageOrderValue.changePercent").value(533.4));
        }

        @Test
        void breaksRevenueDownByCategory() throws Exception {
            mvc.perform(get("/api/stores/" + alpha + "?" + WINDOW))
                    .andExpect(jsonPath("$.categories[*].category", contains("Outerwear", "Accessories", "Tops")))
                    .andExpect(jsonPath("$.categories[0].revenue").value(220.00))
                    .andExpect(jsonPath("$.categories[0].unitsSold").value(2))
                    .andExpect(jsonPath("$.categories[0].orders").value(2))
                    .andExpect(jsonPath("$.categories[0].revenueSharePercent").value(57.9))
                    .andExpect(jsonPath("$.categories[1].revenue").value(120.00))
                    .andExpect(jsonPath("$.categories[1].unitsSold").value(3))
                    .andExpect(jsonPath("$.categories[1].orders").value(1))
                    .andExpect(jsonPath("$.categories[1].revenueSharePercent").value(31.6))
                    .andExpect(jsonPath("$.categories[2].revenue").value(40.00))
                    .andExpect(jsonPath("$.categories[2].revenueSharePercent").value(10.5));
        }

        @Test
        void storeWithoutOrdersHasZerosAndNoCategories() throws Exception {
            mvc.perform(get("/api/stores/" + charlie + "?" + WINDOW))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.store.city", nullValue()))
                    .andExpect(jsonPath("$.revenue.value").value(0))
                    .andExpect(jsonPath("$.revenue.changePercent", nullValue()))
                    .andExpect(jsonPath("$.orders.value").value(0))
                    .andExpect(jsonPath("$.averageOrderValue.value").value(0))
                    .andExpect(jsonPath("$.categories", empty()));
        }

        @Test
        void totalsEqualTheDashboardSummaryForTheStore() throws Exception {
            for (long store : List.of(alpha, bravo, charlie)) {
                String detail = body("/api/stores/" + store + "?" + WINDOW);
                String summary = body("/api/dashboard/summary?" + WINDOW + "&storeId=" + store);
                for (String metric : List.of("revenue", "orders", "unitsSold", "averageOrderValue")) {
                    assertThat(decimal(detail, "$." + metric + ".value"))
                            .as("%s of store %d", metric, store)
                            .isEqualByComparingTo(decimal(summary, "$." + metric + ".value"));
                    assertThat(decimal(detail, "$." + metric + ".previousValue"))
                            .isEqualByComparingTo(decimal(summary, "$." + metric + ".previousValue"));
                }
                BigDecimal categoryRevenue = sum(detail, "$.categories[*].revenue");
                assertThat(categoryRevenue).isEqualByComparingTo(decimal(detail, "$.revenue.value"));
            }
        }
    }

    @Nested
    class RevenueSeries {

        @Test
        void zeroFillsEveryDayAndIgnoresReceiptsWithoutItems() throws Exception {
            mvc.perform(get("/api/stores/" + alpha + "/revenue?" + WINDOW + "&granularity=day"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(alpha))
                    .andExpect(jsonPath("$.granularity").value("day"))
                    .andExpect(jsonPath("$.points", hasSize(14)))
                    .andExpect(jsonPath("$.points[0].periodStart").value("2026-06-01"))
                    .andExpect(jsonPath("$.points[0].revenue").value(140.00))
                    .andExpect(jsonPath("$.points[0].orders").value(1))
                    .andExpect(jsonPath("$.points[1].revenue").value(0))
                    .andExpect(jsonPath("$.points[3].periodStart").value("2026-06-04"))
                    .andExpect(jsonPath("$.points[3].orders").value(0))
                    .andExpect(jsonPath("$.points[7].revenue").value(120.00))
                    .andExpect(jsonPath("$.points[8].revenue").value(120.00))
                    .andExpect(jsonPath("$.points[13].revenue").value(0))
                    .andExpect(jsonPath("$.points[13].complete").value(true));
        }

        @Test
        void marksPartialWeeks() throws Exception {
            mvc.perform(get("/api/stores/" + alpha + "/revenue?from=2026-06-03&to=2026-06-10&granularity=week"))
                    .andExpect(jsonPath("$.points", hasSize(2)))
                    .andExpect(jsonPath("$.points[0].periodStart").value("2026-06-01"))
                    .andExpect(jsonPath("$.points[0].revenue").value(0))
                    .andExpect(jsonPath("$.points[0].daysCovered").value(5))
                    .andExpect(jsonPath("$.points[0].bucketDays").value(7))
                    .andExpect(jsonPath("$.points[0].complete").value(false))
                    .andExpect(jsonPath("$.points[1].periodStart").value("2026-06-08"))
                    .andExpect(jsonPath("$.points[1].revenue").value(240.00))
                    .andExpect(jsonPath("$.points[1].orders").value(2))
                    .andExpect(jsonPath("$.points[1].daysCovered").value(3));
        }

        @Test
        void rejectsAnUnknownGranularity() throws Exception {
            mvc.perform(get("/api/stores/" + alpha + "/revenue?granularity=hour"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            "Invalid value 'hour' for parameter 'granularity'. Expected one of: day, week, month."));
        }
    }

    @Nested
    class TopProducts {

        @Test
        void ranksTheStoresProductsByRevenue() throws Exception {
            mvc.perform(get("/api/stores/" + alpha + "/top-products?" + WINDOW))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(alpha))
                    .andExpect(jsonPath("$.products[*].sku", contains("JKT-001", "SCF-001", "TEE-001")))
                    .andExpect(jsonPath("$.products[0].revenue").value(220.00))
                    .andExpect(jsonPath("$.products[0].unitsSold").value(2))
                    .andExpect(jsonPath("$.products[0].averageUnitPrice").value(110.00))
                    .andExpect(jsonPath("$.products[2].revenue").value(40.00));
        }

        @Test
        void respectsTheLimitAndOnlyThisStore() throws Exception {
            mvc.perform(get("/api/stores/" + alpha + "/top-products?" + WINDOW + "&limit=2"))
                    .andExpect(jsonPath("$.products[*].sku", contains("JKT-001", "SCF-001")));
            mvc.perform(get("/api/stores/" + bravo + "/top-products?" + WINDOW))
                    .andExpect(jsonPath("$.products[*].sku", contains("TEE-001")))
                    .andExpect(jsonPath("$.products[0].revenue").value(25.00));
            mvc.perform(get("/api/stores/" + charlie + "/top-products?" + WINDOW))
                    .andExpect(jsonPath("$.products", empty()));
        }

        @Test
        void validatesTheLimit() throws Exception {
            mvc.perform(get("/api/stores/" + alpha + "/top-products?limit=51"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0]").value("'limit' must be less than or equal to 50"));
            mvc.perform(get("/api/stores/" + alpha + "/top-products?limit=0"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class Errors {

        @ParameterizedTest
        @ValueSource(strings = {"", "/revenue", "/top-products"})
        void anotherBusinesssStoreIsNotFound(String suffix) throws Exception {
            mvc.perform(get("/api/stores/" + otherStore + suffix + "?" + WINDOW))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("Store %d was not found.".formatted(otherStore)));
            mvc.perform(get("/api/stores/9999" + suffix))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Store 9999 was not found."));
        }

        @Test
        void rejectsInvalidStoreIds() throws Exception {
            mvc.perform(get("/api/stores/0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0]").value("'storeId' must be greater than 0"));
            mvc.perform(get("/api/stores/abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Invalid value 'abc' for parameter 'storeId'."));
        }
    }

    private String body(String url) throws Exception {
        return mvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static BigDecimal decimal(String json, String path) {
        Object value = JsonPath.read(json, path);
        return new BigDecimal(value.toString());
    }

    private static BigDecimal sum(String json, String path) {
        List<Object> values = JsonPath.read(json, path);
        return values.stream().map(v -> new BigDecimal(v.toString())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
