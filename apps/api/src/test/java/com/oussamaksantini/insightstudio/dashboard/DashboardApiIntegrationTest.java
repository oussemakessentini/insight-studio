package com.oussamaksantini.insightstudio.dashboard;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the dashboard endpoints against PostgreSQL with a small hand-built dataset whose
 * expected totals are worked out below.
 *
 * <p>Business time zone is Europe/Paris (UTC+2 in June). Reporting window 2026-06-01..2026-06-02:
 * <pre>
 * S1 store A  06-01 10:00  P1 x2 @ 40.00 + P2 x1 @ 20.00 = 100.00  (P1 list price is 50.00)
 * S3 store A  06-01 00:30  P2 x3 @ 20.00                 =  60.00  (still May 31 in UTC)
 * S2 store B  06-02 15:00  P3 x1 @ 90.00                 =  90.00
 * -> revenue 250.00, orders 3, units 7, AOV 83.33
 * S4 store B  05-31 12:00  P1 x1 @ 45.00  -> previous period (05-30..05-31)
 * S6 store B  06-03 00:15  P2 x1 @ 20.00  -> excluded (still June 2 in UTC)
 * S5 store A  06-10 12:00  P3 x1 @ 100.00 -> excluded
 * Another business sells 999.00 on 06-01 -> never included
 * </pre>
 */
class DashboardApiIntegrationTest extends PostgresIntegrationTest {

    private static final String WINDOW = "from=2026-06-01&to=2026-06-02";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    long storeA;
    long storeB;
    long storeC;
    long p1;
    long p2;
    long p3;

    @BeforeEach
    void loadFixture() {
        jdbc.execute(TRUNCATE_ALL);

        long business = insertId("INSERT INTO businesses (name, slug, currency, time_zone) VALUES "
                + "('Test Co', 'test-co', 'EUR', 'Europe/Paris') RETURNING id");
        storeA = insertId("INSERT INTO stores (business_id, code, name, city) VALUES (?, 'A', 'Alpha', 'Paris') RETURNING id", business);
        storeB = insertId("INSERT INTO stores (business_id, code, name, city) VALUES (?, 'B', 'Bravo', 'Lyon') RETURNING id", business);
        storeC = insertId("INSERT INTO stores (business_id, code, name) VALUES (?, 'C', 'Charlie') RETURNING id", business);
        p1 = product(business, "P1", "Jacket", "Outerwear", "50.00");
        p2 = product(business, "P2", "Tee", "Tops", "20.00");
        p3 = product(business, "P3", "Boots", "Footwear", "100.00");

        sale(storeA, "S1", "2026-06-01T08:00:00Z", p1, 2, "40.00", p2, 1, "20.00");
        sale(storeB, "S2", "2026-06-02T13:00:00Z", p3, 1, "90.00");
        sale(storeA, "S3", "2026-05-31T22:30:00Z", p2, 3, "20.00");
        sale(storeB, "S4", "2026-05-31T10:00:00Z", p1, 1, "45.00");
        sale(storeA, "S5", "2026-06-10T10:00:00Z", p3, 1, "100.00");
        sale(storeB, "S6", "2026-06-02T22:15:00Z", p2, 1, "20.00");

        long other = insertId("INSERT INTO businesses (name, slug, currency, time_zone) VALUES "
                + "('Other Co', 'other-co', 'USD', 'UTC') RETURNING id");
        long otherStore = insertId("INSERT INTO stores (business_id, code, name) VALUES (?, 'X', 'Other') RETURNING id", other);
        long otherProduct = product(other, "X1", "Other thing", "Misc", "999.00");
        sale(otherStore, "X-1", "2026-06-01T12:00:00Z", otherProduct, 1, "999.00");
    }

    @Test
    void contextDescribesBusinessStoresAndDataRange() throws Exception {
        mvc.perform(get("/api/dashboard/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.slug").value("test-co"))
                .andExpect(jsonPath("$.business.currency").value("EUR"))
                .andExpect(jsonPath("$.stores[*].code", contains("A", "B", "C")))
                .andExpect(jsonPath("$.stores[2].city", nullValue()))
                .andExpect(jsonPath("$.dataRange.from").value("2026-05-31"))
                .andExpect(jsonPath("$.dataRange.to").value("2026-06-10"));
    }

    @Test
    void summaryUsesStoredSalePricesLocalDaysAndPreviousPeriod() throws Exception {
        mvc.perform(get("/api/dashboard/summary?" + WINDOW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.from").value("2026-06-01"))
                .andExpect(jsonPath("$.previousPeriod.from").value("2026-05-30"))
                .andExpect(jsonPath("$.previousPeriod.to").value("2026-05-31"))
                .andExpect(jsonPath("$.revenue.value").value(250.00))
                .andExpect(jsonPath("$.revenue.previousValue").value(45.00))
                .andExpect(jsonPath("$.revenue.changePercent").value(455.6))
                .andExpect(jsonPath("$.orders.value").value(3))
                .andExpect(jsonPath("$.orders.changePercent").value(200.0))
                .andExpect(jsonPath("$.unitsSold.value").value(7))
                .andExpect(jsonPath("$.averageOrderValue.value").value(83.33))
                .andExpect(jsonPath("$.averageOrderValue.previousValue").value(45.00))
                .andExpect(jsonPath("$.averageOrderValue.changePercent").value(85.2));
    }

    @Test
    void summaryFiltersByStore() throws Exception {
        mvc.perform(get("/api/dashboard/summary?" + WINDOW + "&storeId=" + storeA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(storeA))
                .andExpect(jsonPath("$.revenue.value").value(160.00))
                .andExpect(jsonPath("$.orders.value").value(2))
                .andExpect(jsonPath("$.revenue.previousValue").value(0))
                .andExpect(jsonPath("$.revenue.changePercent", nullValue()));
    }

    @Test
    void revenueSeriesFillsEmptyDays() throws Exception {
        mvc.perform(get("/api/dashboard/revenue?from=2026-06-01&to=2026-06-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("day"))
                .andExpect(jsonPath("$.points", hasSize(4)))
                .andExpect(jsonPath("$.points[0].periodStart").value("2026-06-01"))
                .andExpect(jsonPath("$.points[0].revenue").value(160.00))
                .andExpect(jsonPath("$.points[0].orders").value(2))
                .andExpect(jsonPath("$.points[1].revenue").value(90.00))
                .andExpect(jsonPath("$.points[2].revenue").value(20.00))
                .andExpect(jsonPath("$.points[3].revenue").value(0))
                .andExpect(jsonPath("$.points[3].orders").value(0))
                .andExpect(jsonPath("$.points[3].complete").value(true));
    }

    @Test
    void revenueSeriesFlagsPartialBuckets() throws Exception {
        // 2026-06-03 (Wed) .. 2026-06-08 (Mon): week of Jun 1 has 5 days in range, week of Jun 8 has 1.
        mvc.perform(get("/api/dashboard/revenue?from=2026-06-03&to=2026-06-08&granularity=week"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[*].periodStart", contains("2026-06-01", "2026-06-08")))
                .andExpect(jsonPath("$.points[0].daysCovered").value(5))
                .andExpect(jsonPath("$.points[0].bucketDays").value(7))
                .andExpect(jsonPath("$.points[0].complete").value(false))
                .andExpect(jsonPath("$.points[0].revenue").value(20.00))
                .andExpect(jsonPath("$.points[1].daysCovered").value(1));
    }

    @Test
    void revenueSeriesSupportsExplicitGranularity() throws Exception {
        mvc.perform(get("/api/dashboard/revenue?from=2026-06-01&to=2026-06-30&granularity=month"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("month"))
                .andExpect(jsonPath("$.points", hasSize(1)))
                .andExpect(jsonPath("$.points[0].revenue").value(370.00));
    }

    @Test
    void salesByStoreIncludesStoresWithoutSalesAndShares() throws Exception {
        mvc.perform(get("/api/dashboard/sales-by-store?" + WINDOW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevenue").value(250.00))
                .andExpect(jsonPath("$.stores[*].code", contains("A", "B", "C")))
                .andExpect(jsonPath("$.stores[0].revenue").value(160.00))
                .andExpect(jsonPath("$.stores[0].revenueSharePercent").value(64.0))
                .andExpect(jsonPath("$.stores[1].revenueSharePercent").value(36.0))
                .andExpect(jsonPath("$.stores[2].revenue").value(0))
                .andExpect(jsonPath("$.stores[2].orders").value(0));
    }

    @Test
    void topProductsRankByRevenueThenUnits() throws Exception {
        mvc.perform(get("/api/dashboard/top-products?" + WINDOW + "&limit=3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[*].sku", contains("P3", "P2", "P1")))
                .andExpect(jsonPath("$.products[2].revenue").value(80.00))
                .andExpect(jsonPath("$.products[2].unitsSold").value(2))
                .andExpect(jsonPath("$.products[2].averageUnitPrice").value(40.00));
    }

    @Test
    void recentSalesAreNewestFirst() throws Exception {
        mvc.perform(get("/api/dashboard/recent-sales?" + WINDOW + "&limit=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sales[*].receiptNumber", contains("S2", "S1")))
                .andExpect(jsonPath("$.sales[0].storeName").value("Bravo"))
                .andExpect(jsonPath("$.sales[0].soldAt").value("2026-06-02T13:00:00Z"))
                .andExpect(jsonPath("$.sales[1].itemCount").value(3))
                .andExpect(jsonPath("$.sales[1].total").value(100.00));
    }

    @Nested
    class Errors {

        @Test
        void rejectsReversedRange() throws Exception {
            mvc.perform(get("/api/dashboard/summary?from=2026-06-05&to=2026-06-01"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("'from' (2026-06-05) must be on or before 'to' (2026-06-01)."));
        }

        @Test
        void rejectsMalformedDate() throws Exception {
            mvc.perform(get("/api/dashboard/summary?from=06/01/2026"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Invalid value '06/01/2026' for parameter 'from'."));
        }

        @Test
        void rejectsOutOfRangeLimit() throws Exception {
            mvc.perform(get("/api/dashboard/top-products?limit=51"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0]").value("'limit' must be less than or equal to 50"));
        }

        @Test
        void rejectsUnknownGranularity() throws Exception {
            mvc.perform(get("/api/dashboard/revenue?granularity=hour"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void rejectsRangeLongerThanLimit() throws Exception {
            mvc.perform(get("/api/dashboard/summary?from=2020-01-01&to=2026-01-01"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void returnsNotFoundForStoreOfAnotherBusinessOrUnknownStore() throws Exception {
            long otherStore = jdbc.queryForObject("SELECT id FROM stores WHERE code = 'X'", Long.class);
            mvc.perform(get("/api/dashboard/summary?storeId=" + otherStore))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Store %d was not found.".formatted(otherStore)));
            mvc.perform(get("/api/dashboard/summary?storeId=9999"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void returnsNotFoundWhenThereIsNoBusiness() throws Exception {
            jdbc.execute(TRUNCATE_ALL);
            mvc.perform(get("/api/dashboard/context"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value(
                            "No business data found. Start the API with the 'demo' profile to load sample data."));
        }
    }

    private long insertId(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long product(long businessId, String sku, String name, String category, String listPrice) {
        return insertId("INSERT INTO products (business_id, sku, name, category, list_price) VALUES (?, ?, ?, ?, ?) RETURNING id",
                businessId, sku, name, category, new BigDecimal(listPrice));
    }

    /** Inserts a sale; {@code lines} repeats (productId, quantity, unitPrice). */
    private void sale(long storeId, String receipt, String soldAtUtc, Object... lines) {
        long saleId = insertId("INSERT INTO sales (store_id, receipt_number, sold_at) VALUES (?, ?, ?) RETURNING id",
                storeId, receipt, OffsetDateTime.parse(soldAtUtc));
        for (int i = 0; i < lines.length; i += 3) {
            jdbc.update("INSERT INTO sale_items (sale_id, product_id, quantity, unit_price) VALUES (?, ?, ?, ?)",
                    saleId, lines[i], lines[i + 1], new BigDecimal((String) lines[i + 2]));
        }
    }
}
