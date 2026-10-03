package com.oussamaksantini.insightstudio.business;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Business settings (docs/account-management-contract.md §1): the settings read, the currency rule
 * (never relabel money), and the time zone change with its preview: history is re-bucketed, never
 * rewritten.
 */
class BusinessSettingsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    SqlFixture db;
    TestAccounts accounts;
    long business;
    TestUser owner;
    TestUser admin;
    TestUser viewer;

    @BeforeEach
    void setUp() {
        db = new SqlFixture(jdbc);
        db.clear();
        accounts = new TestAccounts(jdbc);
        business = db.business("Settings Co", "settings-co", "EUR", "UTC");
        owner = accounts.member("owner@settings.co", business, Role.OWNER);
        admin = accounts.member("admin@settings.co", business, Role.ADMIN);
        viewer = accounts.member("viewer@settings.co", business, Role.VIEWER);
    }

    private ResultActions patchBusiness(TestUser user, String body) throws Exception {
        return mvc.perform(patch("/api/businesses/" + business).with(as(user, business))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private List<Map<String, Object>> events() {
        return jdbc.queryForList("SELECT action, details::text AS details FROM audit_events WHERE business_id = ? ORDER BY id",
                business);
    }

    @Test
    void everyMemberReadsTheSettings() throws Exception {
        for (TestUser user : List.of(owner, admin, viewer)) {
            mvc.perform(get("/api/businesses/" + business + "/settings").with(as(user, business)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(business))
                    .andExpect(jsonPath("$.name").value("Settings Co"))
                    .andExpect(jsonPath("$.slug").value("settings-co"))
                    .andExpect(jsonPath("$.currency").value("EUR"))
                    .andExpect(jsonPath("$.timeZone").value("UTC"))
                    .andExpect(jsonPath("$.currencyChangeAllowed").value(true))
                    .andExpect(jsonPath("$.currencyLockedReason").value(nullValue()))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }
        mvc.perform(get("/api/businesses/" + business + "/settings").with(as(viewer, business)))
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    @Test
    void currencyChangesOnlyWhileTheBusinessHoldsNoAmounts() throws Exception {
        // Stores, charts and members hold no amounts: still allowed.
        db.store(business, "S1", "Store", null);
        patchBusiness(owner, "{\"currency\":\"usd\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"));
        assertThat(jdbc.queryForObject("SELECT currency FROM businesses WHERE id = ?", String.class, business)).isEqualTo("USD");
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().get("action")).isEqualTo("business.currency_changed");
        assertThat(JsonPath.<String>read(events().getFirst().get("details").toString(), "$.from")).isEqualTo("EUR");

        // The current currency is a no-op: no event.
        patchBusiness(owner, "{\"currency\":\"USD\"}").andExpect(status().isOk());
        assertThat(events()).hasSize(1);

        patchBusiness(owner, "{\"currency\":\"XYZ\"}").andExpect(status().isBadRequest());
        patchBusiness(owner, "{\"currency\":\"EURO\"}").andExpect(status().isBadRequest());
        patchBusiness(admin, "{\"currency\":\"GBP\"}").andExpect(status().isForbidden());
        patchBusiness(viewer, "{\"currency\":\"GBP\"}").andExpect(status().isForbidden());

        db.product(business, "P1", "Widget", "Misc", "9.99");
        String locked = "The currency can't change once the business has products or sales: their amounts are in USD. "
                + "Create a new business for another currency.";
        patchBusiness(owner, "{\"currency\":\"GBP\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(locked));
        // A rename in the same request is not applied either.
        patchBusiness(owner, "{\"name\":\"Other\",\"currency\":\"GBP\"}").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT name || currency FROM businesses WHERE id = ?", String.class, business))
                .isEqualTo("Settings CoUSD");
        // Sending the current currency with data is still fine.
        patchBusiness(owner, "{\"currency\":\"USD\",\"name\":\"Renamed\"}").andExpect(status().isOk());
        mvc.perform(get("/api/businesses/" + business + "/settings").with(as(admin, business)))
                .andExpect(jsonPath("$.currencyChangeAllowed").value(false))
                .andExpect(jsonPath("$.currencyLockedReason").value(locked));
        assertThat(events()).extracting(e -> e.get("action")).containsExactly("business.currency_changed", "business.renamed");
    }

    @Test
    void unverifiedOwnerCannotChangeSettings() throws Exception {
        TestUser unverified = accounts.unverifiedUser("new@settings.co");
        accounts.member(unverified, business, Role.OWNER);
        mvc.perform(patch("/api/businesses/" + business).with(as(unverified, business))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"USD\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/businesses/" + business + "/settings").with(as(unverified, business))).andExpect(status().isOk());
    }

    /**
     * Contract §1: a sale near midnight moves to another local day (and month) after a zone change; no
     * stored row changes, totals over all data are unchanged, and the preview predicted exactly that.
     */
    @Test
    void timeZoneChangeRebucketsSalesAndThePreviewMatches() throws Exception {
        long store = db.store(business, "S1", "Store", null);
        long product = db.product(business, "P1", "Widget", "Misc", "10.00");
        // 23:30 UTC on 31 March is 01:30 on 1 April in Paris (summer time): another day and month.
        db.sale(store, "R-1", "2026-03-31T23:30:00Z", product, 2, "10.00");
        // Midday: the same day in both zones.
        db.sale(store, "R-2", "2026-03-15T12:00:00Z", product, 1, "10.00");
        // 22:30 UTC on 10 May is 00:30 on 11 May in Paris: another day, same month.
        db.sale(store, "R-3", "2026-05-10T22:30:00Z", product, 3, "10.00");
        // A receipt without items is not an order (like every report).
        jdbc.update("INSERT INTO sales (store_id, receipt_number, sold_at) VALUES (?, 'EMPTY', '2026-03-31T23:45:00Z')", store);
        String soldAtBefore = jdbc.queryForObject("SELECT string_agg(sold_at::text, ',' ORDER BY id) FROM sales", String.class);

        String report = "/api/reports/monthly?from=2026-03-01&to=2026-05-31";
        String before = mvc.perform(get(report).with(as(owner, business))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(monthOrders(before)).containsExactly(2, 0, 1);

        mvc.perform(get("/api/businesses/" + business + "/time-zone-preview").param("timeZone", "Europe/Paris")
                        .with(as(owner, business)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("UTC"))
                .andExpect(jsonPath("$.to").value("Europe/Paris"))
                .andExpect(jsonPath("$.salesTotal").value(3))
                .andExpect(jsonPath("$.salesChangingDay").value(2))
                .andExpect(jsonPath("$.salesChangingMonth").value(1))
                .andExpect(jsonPath("$.months", hasSize(2)))
                .andExpect(jsonPath("$.months[0].month").value("2026-04"))
                .andExpect(jsonPath("$.months[0].revenueBefore").value("0.00"))
                .andExpect(jsonPath("$.months[0].revenueAfter").value("20.00"))
                .andExpect(jsonPath("$.months[0].ordersBefore").value(0))
                .andExpect(jsonPath("$.months[0].ordersAfter").value(1))
                .andExpect(jsonPath("$.months[1].month").value("2026-03"))
                .andExpect(jsonPath("$.months[1].revenueBefore").value("30.00"))
                .andExpect(jsonPath("$.months[1].revenueAfter").value("10.00"))
                .andExpect(jsonPath("$.months[1].ordersBefore").value(2))
                .andExpect(jsonPath("$.months[1].ordersAfter").value(1));
        // The preview changes nothing; only the OWNER gets it; the zone is checked.
        assertThat(jdbc.queryForObject("SELECT time_zone FROM businesses WHERE id = ?", String.class, business)).isEqualTo("UTC");
        mvc.perform(get("/api/businesses/" + business + "/time-zone-preview").param("timeZone", "Europe/Paris")
                .with(as(admin, business))).andExpect(status().isForbidden());
        mvc.perform(get("/api/businesses/" + business + "/time-zone-preview").param("timeZone", "Mars/Base")
                .with(as(owner, business))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/businesses/" + business + "/time-zone-preview").with(as(owner, business)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/businesses/" + business + "/time-zone-preview").param("timeZone", "UTC")
                        .with(as(owner, business)))
                .andExpect(jsonPath("$.salesChangingDay").value(0))
                .andExpect(jsonPath("$.months", hasSize(0)));

        patchBusiness(owner, "{\"timeZone\":\"Europe/Paris\"}").andExpect(status().isOk());

        String after = mvc.perform(get(report).with(as(owner, business))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(monthOrders(after)).containsExactly(1, 1, 1);
        assertThat(new BigDecimal(JsonPath.read(after, "$.totals.revenue").toString()))
                .isEqualByComparingTo(new BigDecimal(JsonPath.read(before, "$.totals.revenue").toString()))
                .isEqualByComparingTo("60.00");
        assertThat(JsonPath.<Integer>read(after, "$.totals.orders")).isEqualTo(3);
        // The local day moved: the late-March sale is now on 1 April, the May one on the 11th.
        String day = mvc.perform(get("/api/reports/monthly?from=2026-04-01&to=2026-04-01").with(as(owner, business)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(day, "$.totals.orders")).isEqualTo(1);
        // No stored row changed.
        assertThat(jdbc.queryForObject("SELECT string_agg(sold_at::text, ',' ORDER BY id) FROM sales", String.class))
                .isEqualTo(soldAtBefore);
        assertThat(events()).extracting(e -> e.get("action")).containsExactly("business.time_zone_changed");
    }

    private static List<Integer> monthOrders(String json) {
        return JsonPath.read(json, "$.rows[*].orders");
    }
}
