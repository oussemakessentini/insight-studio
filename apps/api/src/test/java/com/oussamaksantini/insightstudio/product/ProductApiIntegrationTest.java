package com.oussamaksantini.insightstudio.product;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Product endpoints against PostgreSQL with a hand-built dataset (business time zone UTC).
 * Reporting window 2026-06-01..2026-06-14; previous period 2026-05-18..2026-05-31.
 * <pre>
 * Product                     list    in window                                   revenue units orders
 * JKT-001 Denim Jacket        120.00  S1 x1 @ 100.00 (06-01), S3 x1 @ 120.00 (06-08)   220.00    2     2
 * SCF-001 Wool Scarf           40.00  S4 x3 @ 40.00 (06-09)                            120.00    3     1
 * TEE-001 Classic Tee          25.00  S1 x2 @ 20.00 (06-01, store A),
 *                                     S2 x1 @ 25.00 (06-02, store B)                    65.00    3     2
 * TEE-002 Linen Tee            30.00  no sales                                            0.00    0     0
 * SCK-001 Sock 100% Cotton     12.00  no sales                                            0.00    0     0
 * Previous period: S0 TEE-001 x1 @ 20.00 (05-25). Another business also sells a "TEE-001".
 * </pre>
 */
class ProductApiIntegrationTest extends PostgresIntegrationTest {

    private static final String WINDOW = "from=2026-06-01&to=2026-06-14";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    long storeB;
    long tee;
    long jacket;
    long linenTee;
    long otherBusinessProduct;

    @BeforeEach
    void loadFixture() {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();

        long business = db.business("Test Co", "test-co", "EUR", "UTC");
        long storeA = db.store(business, "A", "Alpha", "Paris");
        storeB = db.store(business, "B", "Bravo", "Lyon");
        tee = db.product(business, "TEE-001", "Classic Tee", "Tops", "25.00");
        jacket = db.product(business, "JKT-001", "Denim Jacket", "Outerwear", "120.00");
        linenTee = db.product(business, "TEE-002", "Linen Tee", "Tops", "30.00");
        long scarf = db.product(business, "SCF-001", "Wool Scarf", "Accessories", "40.00");
        db.product(business, "SCK-001", "Sock 100% Cotton", "Accessories", "12.00");

        db.sale(storeA, "S0", "2026-05-25T10:00:00Z", tee, 1, "20.00");
        db.sale(storeA, "S1", "2026-06-01T10:00:00Z", tee, 2, "20.00", jacket, 1, "100.00");
        db.sale(storeB, "S2", "2026-06-02T10:00:00Z", tee, 1, "25.00");
        db.sale(storeA, "S3", "2026-06-08T10:00:00Z", jacket, 1, "120.00");
        db.sale(storeA, "S4", "2026-06-09T10:00:00Z", scarf, 3, "40.00");

        long other = db.business("Other Co", "other-co", "USD", "UTC");
        long otherStore = db.store(other, "X", "Other", null);
        otherBusinessProduct = db.product(other, "TEE-001", "Classic Tee", "Menswear", "99.00");
        db.sale(otherStore, "X-1", "2026-06-01T12:00:00Z", otherBusinessProduct, 5, "99.00");
    }

    @Nested
    class ProductList {

        @Test
        void includesEveryProductWithPerformanceSortedByRevenue() throws Exception {
            mvc.perform(get("/api/products?" + WINDOW))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(5))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.sort").value("revenue"))
                    .andExpect(jsonPath("$.direction").value("desc"))
                    .andExpect(jsonPath("$.items[*].sku", contains("JKT-001", "SCF-001", "TEE-001", "TEE-002", "SCK-001")))
                    .andExpect(jsonPath("$.items[0].revenue").value(220.00))
                    .andExpect(jsonPath("$.items[0].unitsSold").value(2))
                    .andExpect(jsonPath("$.items[0].orders").value(2))
                    .andExpect(jsonPath("$.items[0].averageSellingPrice").value(110.00))
                    .andExpect(jsonPath("$.items[2].listPrice").value(25.00))
                    .andExpect(jsonPath("$.items[2].averageSellingPrice").value(21.67))
                    .andExpect(jsonPath("$.items[3].revenue").value(0))
                    .andExpect(jsonPath("$.items[3].averageSellingPrice", nullValue()));
        }

        @Test
        void searchesNameOrSkuCaseInsensitively() throws Exception {
            mvc.perform(get("/api/products?" + WINDOW + "&q=TEE"))
                    .andExpect(jsonPath("$.query").value("TEE"))
                    .andExpect(jsonPath("$.items[*].sku", contains("TEE-001", "TEE-002")));
            mvc.perform(get("/api/products?" + WINDOW + "&q=jkt"))
                    .andExpect(jsonPath("$.items[*].sku", contains("JKT-001")));
            mvc.perform(get("/api/products?" + WINDOW + "&q=  scarf "))
                    .andExpect(jsonPath("$.query").value("scarf"))
                    .andExpect(jsonPath("$.items[*].sku", contains("SCF-001")));
        }

        @Test
        void searchTreatsWildcardsLiterally() throws Exception {
            mvc.perform(get("/api/products?" + WINDOW).param("q", "%"))
                    .andExpect(jsonPath("$.items[*].sku", contains("SCK-001")));
            mvc.perform(get("/api/products?" + WINDOW).param("q", "_"))
                    .andExpect(jsonPath("$.totalItems").value(0))
                    .andExpect(jsonPath("$.items", empty()));
        }

        @Test
        void filtersByCategoryAndStore() throws Exception {
            mvc.perform(get("/api/products?" + WINDOW + "&category=Tops"))
                    .andExpect(jsonPath("$.totalItems").value(2))
                    .andExpect(jsonPath("$.items[*].sku", contains("TEE-001", "TEE-002")));
            mvc.perform(get("/api/products?" + WINDOW + "&storeId=" + storeB))
                    .andExpect(jsonPath("$.storeId").value(storeB))
                    .andExpect(jsonPath("$.items[0].sku").value("TEE-001"))
                    .andExpect(jsonPath("$.items[0].revenue").value(25.00))
                    .andExpect(jsonPath("$.items[1].revenue").value(0));
        }

        @Test
        void paginatesWithStableOrdering() throws Exception {
            mvc.perform(get("/api/products?" + WINDOW + "&size=2&page=1"))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.size").value(2))
                    .andExpect(jsonPath("$.totalItems").value(5))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.items[*].sku", contains("TEE-001", "TEE-002")));
            mvc.perform(get("/api/products?" + WINDOW + "&size=2&page=5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(5))
                    .andExpect(jsonPath("$.items", empty()));
        }

        @Test
        void sortsByNameAndPrice() throws Exception {
            mvc.perform(get("/api/products?" + WINDOW + "&sort=name"))
                    .andExpect(jsonPath("$.direction").value("asc"))
                    .andExpect(jsonPath("$.items[*].name",
                            contains("Classic Tee", "Denim Jacket", "Linen Tee", "Sock 100% Cotton", "Wool Scarf")));
            mvc.perform(get("/api/products?" + WINDOW + "&sort=price&direction=asc"))
                    .andExpect(jsonPath("$.items[*].sku", contains("SCK-001", "TEE-001", "TEE-002", "SCF-001", "JKT-001")));
        }

        @Test
        void rejectsInvalidParameters() throws Exception {
            mvc.perform(get("/api/products?sort=color"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            "Invalid value 'color' for parameter 'sort'. Expected one of: revenue, units, name, sku, price."));
            mvc.perform(get("/api/products?direction=up")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/products?size=0")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/products?size=101")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/products?page=-1")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/products").param("q", "x".repeat(101)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0]").value("'q' size must be between 0 and 100"));
        }

        @Test
        void categoriesBelongToTheCurrentBusinessOnly() throws Exception {
            mvc.perform(get("/api/products/categories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.categories", contains("Accessories", "Outerwear", "Tops")));
        }
    }

    @Nested
    class ProductDetail {

        @Test
        void comparesPeriodsAndListsHistoricalPrices() throws Exception {
            mvc.perform(get("/api/products/" + tee + "?" + WINDOW))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.product.sku").value("TEE-001"))
                    .andExpect(jsonPath("$.product.listPrice").value(25.00))
                    .andExpect(jsonPath("$.previousPeriod.from").value("2026-05-18"))
                    .andExpect(jsonPath("$.revenue.value").value(65.00))
                    .andExpect(jsonPath("$.revenue.previousValue").value(20.00))
                    .andExpect(jsonPath("$.revenue.changePercent").value(225.0))
                    .andExpect(jsonPath("$.unitsSold.value").value(3))
                    .andExpect(jsonPath("$.orders.value").value(2))
                    .andExpect(jsonPath("$.averageSellingPrice.value").value(21.67))
                    .andExpect(jsonPath("$.averageSellingPrice.previousValue").value(20.00))
                    .andExpect(jsonPath("$.averageSellingPrice.changePercent").value(8.4))
                    .andExpect(jsonPath("$.priceHistory", hasSize(2)))
                    .andExpect(jsonPath("$.priceHistory[0].unitPrice").value(20.00))
                    .andExpect(jsonPath("$.priceHistory[0].firstSoldOn").value("2026-06-01"))
                    .andExpect(jsonPath("$.priceHistory[0].unitsSold").value(2))
                    .andExpect(jsonPath("$.priceHistory[1].unitPrice").value(25.00))
                    .andExpect(jsonPath("$.priceHistory[1].lastSoldOn").value("2026-06-02"));
        }

        @Test
        void respectsStoreFilter() throws Exception {
            mvc.perform(get("/api/products/" + tee + "?" + WINDOW + "&storeId=" + storeB))
                    .andExpect(jsonPath("$.revenue.value").value(25.00))
                    .andExpect(jsonPath("$.priceHistory[*].unitPrice", contains(25.00)));
        }

        @Test
        void productWithoutSalesHasNoAveragePrice() throws Exception {
            mvc.perform(get("/api/products/" + linenTee + "?" + WINDOW))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.revenue.value").value(0))
                    .andExpect(jsonPath("$.averageSellingPrice.value", nullValue()))
                    .andExpect(jsonPath("$.averageSellingPrice.changePercent", nullValue()))
                    .andExpect(jsonPath("$.priceHistory", empty()));
        }

        @Test
        void productsOfOtherBusinessesAreNotFound() throws Exception {
            mvc.perform(get("/api/products/" + otherBusinessProduct))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Product %d was not found.".formatted(otherBusinessProduct)));
            mvc.perform(get("/api/products/999999")).andExpect(status().isNotFound());
            mvc.perform(get("/api/products/" + otherBusinessProduct + "/sales-trend")).andExpect(status().isNotFound());
        }

        @Test
        void salesTrendIsZeroFilledAndUsesPricesCharged() throws Exception {
            mvc.perform(get("/api/products/" + tee + "/sales-trend?from=2026-06-01&to=2026-06-03"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productId").value(tee))
                    .andExpect(jsonPath("$.granularity").value("day"))
                    .andExpect(jsonPath("$.points", hasSize(3)))
                    .andExpect(jsonPath("$.points[0].revenue").value(40.00))
                    .andExpect(jsonPath("$.points[0].unitsSold").value(2))
                    .andExpect(jsonPath("$.points[0].averageUnitPrice").value(20.00))
                    .andExpect(jsonPath("$.points[1].averageUnitPrice").value(25.00))
                    .andExpect(jsonPath("$.points[2].revenue").value(0))
                    .andExpect(jsonPath("$.points[2].averageUnitPrice", nullValue()));
        }

        @Test
        void salesTrendFlagsPartialBuckets() throws Exception {
            // 06-03 (Wed) .. 06-14 (Sun): the week of Jun 1 has 5 days in range; S1 on 06-01 is outside it.
            mvc.perform(get("/api/products/" + jacket + "/sales-trend?from=2026-06-03&to=2026-06-14&granularity=week"))
                    .andExpect(jsonPath("$.points[*].periodStart", contains("2026-06-01", "2026-06-08")))
                    .andExpect(jsonPath("$.points[0].complete").value(false))
                    .andExpect(jsonPath("$.points[0].daysCovered").value(5))
                    .andExpect(jsonPath("$.points[0].revenue").value(0))
                    .andExpect(jsonPath("$.points[1].complete").value(true))
                    .andExpect(jsonPath("$.points[1].revenue").value(120.00));
        }
    }
}
