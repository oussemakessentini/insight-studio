package com.oussamaksantini.insightstudio.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the report endpoints against PostgreSQL with a small hand-built dataset.
 *
 * <p>Business time zone is Europe/Paris (UTC+1 until 2026-03-29, then UTC+2). Reporting window
 * 2026-03-20..2026-06-10, i.e. March (12 of 31 days), April and May (complete), June (10 of 30 days):
 * <pre>
 * S0 store A  03-19 23:59 local  P1 x1 @ 50.00                     -> excluded (before the window)
 * S1 store A  04-20              P1 x1 @ 50.00             =  50.00  April
 * S2 store A  05-01 00:30 local  P2 x2 @ 20.00             =  40.00  May (still April 30 in UTC)
 * S6 store B  05-10              P2 x5 @ 20.00             = 100.00  May
 * S3 store B  06-01 00:30 local  P1 x1 @ 40.00 + P2 x1 @ 20 = 60.00  June (still May 31 in UTC)
 * S4 store A  06-05              P3 x1 @ 90.00             =  90.00  June
 * S5 store A  06-11 00:30 local  P3 x1 @ 100.00                    -> excluded (still June 10 in UTC)
 * S-EMPTY store B 06-02          no line items                     -> not an order
 * Another business sells 999.00 in June                            -> never included
 *
 * Months:  March 0 | April 50.00 (1 order, 1 unit) | May 140.00 (2, 7) +180.0% | June 150.00 (2, 3) +7.1%
 * Totals:  revenue 340.00, orders 5, units 11, AOV 68.00
 * Categories: Tops 160.00 (8 units, 3 orders, 47.1%) | Footwear 90.00 (1, 1, 26.5%)
 *             | Outerwear 90.00 (2, 2, 26.5%) | =SUM(A1,"x") no sales
 *   S3 contains Tops and Outerwear, so category orders add up to 6 while the total is 5.
 * Store A: 180.00, 3 orders, 4 units. Store B: 160.00, 2 orders, 7 units.
 * </pre>
 */
class ReportApiIntegrationTest extends PostgresIntegrationTest {

    private static final String WINDOW = "from=2026-03-20&to=2026-06-10";
    /** A category name from the data that a spreadsheet would run as a formula, with a comma and quotes. */
    private static final String FORMULA_CATEGORY = "=SUM(A1,\"x\")";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    long storeA;
    long storeB;
    long otherStore;

    @BeforeEach
    void loadFixture() {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();

        long business = db.business("Test Co", "test-co", "EUR", "Europe/Paris");
        storeA = db.store(business, "A", "Alpha", "Paris");
        storeB = db.store(business, "B", "Bravo", "Lyon");
        long p1 = db.product(business, "P1", "Jacket", "Outerwear", "50.00");
        long p2 = db.product(business, "P2", "Tee", "Tops", "20.00");
        long p3 = db.product(business, "P3", "Boots", "Footwear", "100.00");
        db.product(business, "P4", "Unsold thing", FORMULA_CATEGORY, "10.00");

        db.sale(storeA, "S0", "2026-03-19T22:59:00Z", p1, 1, "50.00");
        db.sale(storeA, "S1", "2026-04-20T10:00:00Z", p1, 1, "50.00");
        db.sale(storeA, "S2", "2026-04-30T22:30:00Z", p2, 2, "20.00");
        db.sale(storeB, "S6", "2026-05-10T10:00:00Z", p2, 5, "20.00");
        db.sale(storeB, "S3", "2026-05-31T22:30:00Z", p1, 1, "40.00", p2, 1, "20.00");
        db.sale(storeA, "S4", "2026-06-05T10:00:00Z", p3, 1, "90.00");
        db.sale(storeA, "S5", "2026-06-10T22:30:00Z", p3, 1, "100.00");
        db.sale(storeB, "S-EMPTY", "2026-06-02T10:00:00Z");

        long other = db.business("Other Co", "other-co", "USD", "UTC");
        otherStore = db.store(other, "X", "Other", null);
        long otherProduct = db.product(other, "X1", "Other thing", "Misc", "999.00");
        db.sale(otherStore, "X-1", "2026-06-01T12:00:00Z", otherProduct, 1, "999.00");
    }

    @Nested
    class Monthly {

        @Test
        void zeroFillsMonthsFlagsPartialMonthsAndComparesWithThePreviousRow() throws Exception {
            mvc.perform(get("/api/reports/monthly?" + WINDOW))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.period.from").value("2026-03-20"))
                    .andExpect(jsonPath("$.period.to").value("2026-06-10"))
                    .andExpect(jsonPath("$.storeId", nullValue()))
                    .andExpect(jsonPath("$.rows", hasSize(4)))
                    .andExpect(jsonPath("$.rows[*].month", contains("2026-03-01", "2026-04-01", "2026-05-01", "2026-06-01")))
                    .andExpect(jsonPath("$.rows[0].revenue").value(0))
                    .andExpect(jsonPath("$.rows[0].orders").value(0))
                    .andExpect(jsonPath("$.rows[0].averageOrderValue").value(0))
                    .andExpect(jsonPath("$.rows[0].revenueChangePercent", nullValue()))
                    .andExpect(jsonPath("$.rows[0].daysCovered").value(12))
                    .andExpect(jsonPath("$.rows[0].daysInMonth").value(31))
                    .andExpect(jsonPath("$.rows[0].complete").value(false))
                    .andExpect(jsonPath("$.rows[1].revenue").value(50.00))
                    .andExpect(jsonPath("$.rows[1].revenueChangePercent", nullValue()))
                    .andExpect(jsonPath("$.rows[1].complete").value(true))
                    .andExpect(jsonPath("$.rows[2].revenue").value(140.00))
                    .andExpect(jsonPath("$.rows[2].orders").value(2))
                    .andExpect(jsonPath("$.rows[2].unitsSold").value(7))
                    .andExpect(jsonPath("$.rows[2].averageOrderValue").value(70.00))
                    .andExpect(jsonPath("$.rows[2].revenueChangePercent").value(180.0))
                    .andExpect(jsonPath("$.rows[2].daysInMonth").value(31))
                    .andExpect(jsonPath("$.rows[2].complete").value(true))
                    .andExpect(jsonPath("$.rows[3].revenue").value(150.00))
                    .andExpect(jsonPath("$.rows[3].orders").value(2))
                    .andExpect(jsonPath("$.rows[3].unitsSold").value(3))
                    .andExpect(jsonPath("$.rows[3].revenueChangePercent").value(7.1))
                    .andExpect(jsonPath("$.rows[3].daysCovered").value(10))
                    .andExpect(jsonPath("$.rows[3].daysInMonth").value(30))
                    .andExpect(jsonPath("$.rows[3].complete").value(false))
                    .andExpect(jsonPath("$.totals.revenue").value(340.00))
                    .andExpect(jsonPath("$.totals.orders").value(5))
                    .andExpect(jsonPath("$.totals.unitsSold").value(11))
                    .andExpect(jsonPath("$.totals.averageOrderValue").value(68.00));
        }

        @Test
        void filtersByStore() throws Exception {
            mvc.perform(get("/api/reports/monthly?" + WINDOW + "&storeId=" + storeA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(storeA))
                    .andExpect(jsonPath("$.rows[*].revenue", contains(0.0, 50.0, 40.0, 90.0)))
                    .andExpect(jsonPath("$.rows[3].revenueChangePercent").value(125.0))
                    .andExpect(jsonPath("$.totals.revenue").value(180.00))
                    .andExpect(jsonPath("$.totals.orders").value(3))
                    .andExpect(jsonPath("$.totals.unitsSold").value(4))
                    .andExpect(jsonPath("$.totals.averageOrderValue").value(60.00));
        }

        @Test
        void singleMonthWindowHasOnePartialRow() throws Exception {
            mvc.perform(get("/api/reports/monthly?from=2026-06-02&to=2026-06-02"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows", hasSize(1)))
                    .andExpect(jsonPath("$.rows[0].revenue").value(0))
                    .andExpect(jsonPath("$.rows[0].orders").value(0))
                    .andExpect(jsonPath("$.rows[0].daysCovered").value(1))
                    .andExpect(jsonPath("$.rows[0].complete").value(false));
        }
    }

    @Nested
    class Categories {

        @Test
        void listsEveryCatalogueCategoryByRevenue() throws Exception {
            mvc.perform(get("/api/reports/categories?" + WINDOW))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.period.from").value("2026-03-20"))
                    .andExpect(jsonPath("$.rows[*].category", contains("Tops", "Footwear", "Outerwear", FORMULA_CATEGORY)))
                    .andExpect(jsonPath("$.rows[0].revenue").value(160.00))
                    .andExpect(jsonPath("$.rows[0].unitsSold").value(8))
                    .andExpect(jsonPath("$.rows[0].orders").value(3))
                    .andExpect(jsonPath("$.rows[0].revenueSharePercent").value(47.1))
                    .andExpect(jsonPath("$.rows[0].averageUnitPrice").value(20.00))
                    .andExpect(jsonPath("$.rows[1].revenue").value(90.00))
                    .andExpect(jsonPath("$.rows[1].revenueSharePercent").value(26.5))
                    .andExpect(jsonPath("$.rows[2].unitsSold").value(2))
                    .andExpect(jsonPath("$.rows[2].orders").value(2))
                    .andExpect(jsonPath("$.rows[2].averageUnitPrice").value(45.00))
                    .andExpect(jsonPath("$.rows[3].revenue").value(0))
                    .andExpect(jsonPath("$.rows[3].unitsSold").value(0))
                    .andExpect(jsonPath("$.rows[3].orders").value(0))
                    .andExpect(jsonPath("$.rows[3].revenueSharePercent").value(0))
                    .andExpect(jsonPath("$.rows[3].averageUnitPrice").value(0))
                    .andExpect(jsonPath("$.totals.revenue").value(340.00))
                    .andExpect(jsonPath("$.totals.unitsSold").value(11))
                    // Distinct receipts: S3 counts once although it spans two categories.
                    .andExpect(jsonPath("$.totals.orders").value(5))
                    .andExpect(jsonPath("$.totals.averageOrderValue").value(68.00))
                    .andExpect(jsonPath("$.totals.averageUnitPrice").value(30.91));
        }

        @Test
        void filtersByStoreAndKeepsCategoriesWithoutSales() throws Exception {
            mvc.perform(get("/api/reports/categories?" + WINDOW + "&storeId=" + storeB))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(storeB))
                    .andExpect(jsonPath("$.rows", hasSize(4)))
                    .andExpect(jsonPath("$.rows[0].category").value("Tops"))
                    .andExpect(jsonPath("$.rows[1].category").value("Outerwear"))
                    // The two categories without sales follow; their relative order depends on the collation.
                    .andExpect(jsonPath("$.rows[2:].category", containsInAnyOrder("Footwear", FORMULA_CATEGORY)))
                    .andExpect(jsonPath("$.rows[*].revenue", contains(120.0, 40.0, 0.0, 0.0)))
                    .andExpect(jsonPath("$.totals.revenue").value(160.00))
                    .andExpect(jsonPath("$.totals.orders").value(2))
                    .andExpect(jsonPath("$.totals.unitsSold").value(7));
        }
    }

    @Nested
    class AgreementWithDashboard {

        @Test
        void monthlyAndCategoryTotalsEqualTheDashboardSummary() throws Exception {
            for (String filter : new String[] {
                    WINDOW, WINDOW + "&storeId=" + storeA, WINDOW + "&storeId=" + storeB,
                    "from=2026-05-15&to=2026-06-03", "from=2026-06-02&to=2026-06-02"}) {
                String summary = body("/api/dashboard/summary?" + filter);
                String monthly = body("/api/reports/monthly?" + filter);
                String categories = body("/api/reports/categories?" + filter);

                BigDecimal revenue = number(summary, "$.revenue.value");
                long orders = number(summary, "$.orders.value").longValueExact();
                long units = number(summary, "$.unitsSold.value").longValueExact();

                assertThat(number(monthly, "$.totals.revenue")).as("monthly revenue for %s", filter).isEqualByComparingTo(revenue);
                assertThat(number(monthly, "$.totals.orders").longValueExact()).as("monthly orders for %s", filter).isEqualTo(orders);
                assertThat(number(monthly, "$.totals.unitsSold").longValueExact()).as("monthly units for %s", filter).isEqualTo(units);
                assertThat(number(monthly, "$.totals.averageOrderValue"))
                        .isEqualByComparingTo(number(summary, "$.averageOrderValue.value"));
                assertThat(number(categories, "$.totals.revenue")).as("category revenue for %s", filter).isEqualByComparingTo(revenue);
                assertThat(number(categories, "$.totals.orders").longValueExact()).as("category orders for %s", filter).isEqualTo(orders);
                assertThat(number(categories, "$.totals.unitsSold").longValueExact()).as("category units for %s", filter).isEqualTo(units);
            }
        }

        private String body(String url) throws Exception {
            return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        }

        private BigDecimal number(String json, String path) {
            Object value = JsonPath.read(json, path);
            return new BigDecimal(value.toString());
        }
    }

    @Nested
    class CsvExport {

        @Test
        void monthlyCsvIsAnAttachmentWithTheSameRows() throws Exception {
            String expected = String.join("\r\n",
                    "month,revenue,orders,units_sold,average_order_value,revenue_change_percent,days_covered,days_in_month,complete",
                    "2026-03-01,0.00,0,0,0.00,,12,31,false",
                    "2026-04-01,50.00,1,1,50.00,,30,30,true",
                    "2026-05-01,140.00,2,7,70.00,180.0,31,31,true",
                    "2026-06-01,150.00,2,3,75.00,7.1,10,30,false",
                    "");
            mvc.perform(get("/api/reports/monthly.csv?" + WINDOW))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"monthly-2026-03-20-to-2026-06-10.csv\""))
                    .andExpect(content().string(expected));
        }

        @Test
        void categoryCsvQuotesFieldsAndNeutralizesFormulas() throws Exception {
            String expected = String.join("\r\n",
                    "category,revenue,units_sold,orders,revenue_share_percent,average_unit_price",
                    "Tops,160.00,8,3,47.1,20.00",
                    "Footwear,90.00,1,1,26.5,90.00",
                    "Outerwear,90.00,2,2,26.5,45.00",
                    "\"'=SUM(A1,\"\"x\"\")\",0.00,0,0,0.0,0.00",
                    "");
            mvc.perform(get("/api/reports/categories.csv?" + WINDOW))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("text/csv;charset=UTF-8"))
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"categories-2026-03-20-to-2026-06-10.csv\""))
                    .andExpect(content().string(expected));
        }

        @Test
        void csvRespectsTheStoreFilter() throws Exception {
            String csv = mvc.perform(get("/api/reports/monthly.csv?" + WINDOW + "&storeId=" + storeB))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(csv).contains("2026-05-01,100.00,1,5,100.00,,31,31,true\r\n");
        }

        @Test
        void csvErrorsAreProblemDetails() throws Exception {
            mvc.perform(get("/api/reports/categories.csv?from=2026-06-05&to=2026-06-01"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("'from' (2026-06-05) must be on or before 'to' (2026-06-01)."));
        }
    }

    @Nested
    class Validation {

        @Test
        void rejectsFromAfterTo() throws Exception {
            mvc.perform(get("/api/reports/monthly?from=2026-06-05&to=2026-06-01"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("'from' (2026-06-05) must be on or before 'to' (2026-06-01)."));
        }

        @Test
        void rejectsMalformedDate() throws Exception {
            mvc.perform(get("/api/reports/categories?to=06/01/2026"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Invalid value '06/01/2026' for parameter 'to'."));
        }

        @Test
        void rejectsNonPositiveStoreId() throws Exception {
            mvc.perform(get("/api/reports/monthly.csv?storeId=0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0]").value("'storeId' must be greater than 0"));
        }

        @Test
        void rejectsRangeLongerThanLimit() throws Exception {
            mvc.perform(get("/api/reports/monthly?from=2020-01-01&to=2026-01-01"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void returnsNotFoundForStoreOfAnotherBusiness() throws Exception {
            mvc.perform(get("/api/reports/categories?storeId=" + otherStore))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Store %d was not found.".formatted(otherStore)));
            mvc.perform(get("/api/reports/monthly.csv?storeId=" + otherStore))
                    .andExpect(status().isNotFound());
        }
    }
}
