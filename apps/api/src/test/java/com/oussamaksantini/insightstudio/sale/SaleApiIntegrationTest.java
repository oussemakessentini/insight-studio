package com.oussamaksantini.insightstudio.sale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * Sales endpoints against PostgreSQL. Business time zone is Europe/Paris (UTC+2 in June).
 * <pre>
 * Receipt  store  sold at (UTC)      local date  lines                                  lines units total
 * R-001    A      06-01 08:00        06-01       TEE x2 @ 20.00 (list 25), JKT x1 @ 100.00  2     3  140.00
 * R-002    B      06-02 13:00        06-02       TEE x1 @ 25.00                            1     1   25.00
 * R-003    A      05-31 22:30        06-01       JKT x1 @ 120.00                           1     1  120.00
 * R-004    A      06-03 09:00        06-03       (no lines) -> not an order: never listed or counted
 * R-005    B      06-02 22:30        06-03       TEE x3 @ 25.00                            1     3   75.00
 * R-OLD    A      05-20 10:00        05-20       TEE x1 @ 20.00  -> outside every window used below
 * Another business has receipt X-1 on 06-01.
 * </pre>
 */
class SaleApiIntegrationTest extends PostgresIntegrationTest {

    /** R-001, R-002, R-003. */
    private static final String TWO_DAYS = "from=2026-06-01&to=2026-06-02";
    /** Adds R-005; R-004 falls in this window too but has no items, so it is not an order. */
    private static final String THREE_DAYS = "from=2026-06-01&to=2026-06-03";

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    long storeA;
    long storeB;
    long jacket;
    long r001;
    long r004;
    long otherSale;
    long otherProduct;

    @BeforeEach
    void loadFixture() {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();

        long business = db.business("Test Co", "test-co", "EUR", "Europe/Paris");

        mvc = TestAccounts.ownerMvc(context, jdbc, business);
        storeA = db.store(business, "A", "Alpha", "Paris");
        storeB = db.store(business, "B", "Bravo", null);
        long tee = db.product(business, "TEE-001", "Tee", "Tops", "25.00");
        jacket = db.product(business, "JKT-001", "Jacket", "Outerwear", "120.00");

        r001 = db.sale(storeA, "R-001", "2026-06-01T08:00:00Z", tee, 2, "20.00", jacket, 1, "100.00");
        db.sale(storeB, "R-002", "2026-06-02T13:00:00Z", tee, 1, "25.00");
        db.sale(storeA, "R-003", "2026-05-31T22:30:00Z", jacket, 1, "120.00");
        r004 = db.sale(storeA, "R-004", "2026-06-03T09:00:00Z");
        db.sale(storeB, "R-005", "2026-06-02T22:30:00Z", tee, 3, "25.00");
        db.sale(storeA, "R-OLD", "2026-05-20T10:00:00Z", tee, 1, "20.00");

        long other = db.business("Other Co", "other-co", "USD", "UTC");
        long otherStore = db.store(other, "X", "Other", null);
        otherProduct = db.product(other, "X1", "Other thing", "Misc", "9.00");
        otherSale = db.sale(otherStore, "X-1", "2026-06-01T12:00:00Z", otherProduct, 1, "9.00");
    }

    @Nested
    class SaleList {

        @Test
        void listsReceiptsInLocalDateWindowNewestFirst() throws Exception {
            mvc.perform(get("/api/sales?" + TWO_DAYS))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sort").value("newest"))
                    .andExpect(jsonPath("$.totalItems").value(3))
                    .andExpect(jsonPath("$.product", nullValue()))
                    .andExpect(jsonPath("$.items[*].receiptNumber", contains("R-002", "R-001", "R-003")))
                    .andExpect(jsonPath("$.items[1].soldAt").value("2026-06-01T08:00:00Z"))
                    .andExpect(jsonPath("$.items[1].storeName").value("Alpha"))
                    .andExpect(jsonPath("$.items[1].storeCode").value("A"))
                    .andExpect(jsonPath("$.items[1].lineCount").value(2))
                    .andExpect(jsonPath("$.items[1].unitCount").value(3))
                    .andExpect(jsonPath("$.items[1].total").value(140.00));
        }

        @Test
        void excludesReceiptsWithoutLinesSoCountsMatchTheDashboard() throws Exception {
            mvc.perform(get("/api/sales?" + THREE_DAYS))
                    .andExpect(jsonPath("$.totalItems").value(4))
                    .andExpect(jsonPath("$.items[*].receiptNumber", contains("R-005", "R-002", "R-001", "R-003")));

            for (String filter : new String[] {THREE_DAYS, THREE_DAYS + "&storeId=" + storeA}) {
                String sales = body("/api/sales?" + filter + "&size=100");
                String summary = body("/api/dashboard/summary?" + filter);
                String byStore = body("/api/dashboard/sales-by-store?" + filter);

                int listed = JsonPath.read(sales, "$.totalItems");
                List<Integer> storeOrders = JsonPath.read(byStore, "$.stores[*].orders");
                List<Number> totals = JsonPath.read(sales, "$.items[*].total");
                assertThat(listed)
                        .as("sales list vs dashboard orders for %s", filter)
                        .isEqualTo((Integer) JsonPath.read(summary, "$.orders.value"))
                        .isEqualTo(storeOrders.stream().mapToInt(Integer::intValue).sum());
                assertThat(totals.stream().mapToDouble(Number::doubleValue).sum())
                        .as("sum of receipt totals vs dashboard revenue for %s", filter)
                        .isCloseTo(((Number) JsonPath.read(summary, "$.revenue.value")).doubleValue(), within(0.001));
            }
        }

        @Test
        void filtersByStore() throws Exception {
            mvc.perform(get("/api/sales?" + TWO_DAYS + "&storeId=" + storeB))
                    .andExpect(jsonPath("$.storeId").value(storeB))
                    .andExpect(jsonPath("$.items[*].receiptNumber", contains("R-002")));
        }

        @Test
        void sortsOldestFirstAndByLargestTotal() throws Exception {
            mvc.perform(get("/api/sales?" + THREE_DAYS + "&sort=oldest"))
                    .andExpect(jsonPath("$.items[*].receiptNumber", contains("R-003", "R-001", "R-002", "R-005")));
            mvc.perform(get("/api/sales?" + THREE_DAYS + "&sort=LARGEST"))
                    .andExpect(jsonPath("$.sort").value("largest"))
                    .andExpect(jsonPath("$.items[*].receiptNumber", contains("R-001", "R-003", "R-005", "R-002")));
        }

        @Test
        void searchesReceiptNumbersLiterally() throws Exception {
            mvc.perform(get("/api/sales?" + THREE_DAYS + "&q=r-00"))
                    .andExpect(jsonPath("$.totalItems").value(4));
            mvc.perform(get("/api/sales?" + THREE_DAYS + "&q= 002 "))
                    .andExpect(jsonPath("$.query").value("002"))
                    .andExpect(jsonPath("$.items[*].receiptNumber", contains("R-002")));
            mvc.perform(get("/api/sales?" + THREE_DAYS).param("q", "R_0"))
                    .andExpect(jsonPath("$.totalItems").value(0));
            mvc.perform(get("/api/sales?" + THREE_DAYS).param("q", "%"))
                    .andExpect(jsonPath("$.items", empty()));
        }

        @Test
        void filtersToReceiptsContainingAProductWithWholeReceiptTotals() throws Exception {
            mvc.perform(get("/api/sales?" + THREE_DAYS + "&productId=" + jacket))
                    .andExpect(jsonPath("$.product.sku").value("JKT-001"))
                    .andExpect(jsonPath("$.items[*].receiptNumber", contains("R-001", "R-003")))
                    .andExpect(jsonPath("$.items[0].total").value(140.00));
        }

        @Test
        void paginatesWithTotals() throws Exception {
            mvc.perform(get("/api/sales?" + THREE_DAYS + "&size=2&page=1"))
                    .andExpect(jsonPath("$.totalItems").value(4))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.items[*].receiptNumber", contains("R-001", "R-003")));
            mvc.perform(get("/api/sales?" + THREE_DAYS + "&size=2&page=9"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(4))
                    .andExpect(jsonPath("$.items", empty()));
        }

        @Test
        void rejectsInvalidParameters() throws Exception {
            mvc.perform(get("/api/sales?sort=cheapest"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            "Invalid value 'cheapest' for parameter 'sort'. Expected one of: newest, oldest, largest."));
            mvc.perform(get("/api/sales?size=101")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/sales?page=-1")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/sales").param("q", "x".repeat(41))).andExpect(status().isBadRequest());
            mvc.perform(get("/api/sales?productId=" + otherProduct))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Product %d was not found.".formatted(otherProduct)));
        }
    }

    @Nested
    class SaleDetail {

        @Test
        void showsLinesWithHistoricalPrices() throws Exception {
            mvc.perform(get("/api/sales/" + r001))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.receiptNumber").value("R-001"))
                    .andExpect(jsonPath("$.soldAt").value("2026-06-01T08:00:00Z"))
                    .andExpect(jsonPath("$.store.name").value("Alpha"))
                    .andExpect(jsonPath("$.store.city").value("Paris"))
                    .andExpect(jsonPath("$.lineCount").value(2))
                    .andExpect(jsonPath("$.unitCount").value(3))
                    .andExpect(jsonPath("$.total").value(140.00))
                    .andExpect(jsonPath("$.lines[*].sku", contains("TEE-001", "JKT-001")))
                    .andExpect(jsonPath("$.lines[0].quantity").value(2))
                    .andExpect(jsonPath("$.lines[0].unitPrice").value(20.00))
                    .andExpect(jsonPath("$.lines[0].lineTotal").value(40.00))
                    .andExpect(jsonPath("$.lines[0].currentListPrice").value(25.00))
                    .andExpect(jsonPath("$.lines[1].lineTotal").value(100.00));
        }

        @Test
        void receiptWithoutLinesCanStillBeOpened() throws Exception {
            mvc.perform(get("/api/sales/" + r004))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lines", empty()))
                    .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        void salesOfOtherBusinessesAreNotFound() throws Exception {
            mvc.perform(get("/api/sales/" + otherSale))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Sale %d was not found.".formatted(otherSale)));
            mvc.perform(get("/api/sales/999999")).andExpect(status().isNotFound());
            mvc.perform(get("/api/sales/abc")).andExpect(status().isBadRequest());
        }
    }

    private String body(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
