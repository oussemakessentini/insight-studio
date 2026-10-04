package com.oussamaksantini.insightstudio.billing;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Plan limits (docs/billing-contract.md §2, §3) with the shipped Free limits: each creation path is
 * refused with a {@code plan_limit} problem at the limit, concurrent creations never exceed it, and a
 * downgrade keeps everything (readable, editable, deletable, exportable) while refusing more.
 */
class PlanLimitsIntegrationTest extends BillingIntegrationTest {

    Shop shop;

    @BeforeEach
    void setUp() {
        shop = shop("Limits Co");
    }

    // ---------------------------------------------------------------- helpers

    private int createStore(String code) throws Exception {
        return mvc.perform(json(post("/api/stores"), "{\"code\":\"%s\",\"name\":\"Store %s\",\"city\":\"Boston\"}".formatted(code, code))
                .with(as(shop.owner(), shop.id()))).andReturn().getResponse().getStatus();
    }

    private int createChart(String title) throws Exception {
        return mvc.perform(json(post("/api/charts"), CHART.formatted(title)).with(as(shop.owner(), shop.id())))
                .andReturn().getResponse().getStatus();
    }

    private int createDashboard(String name) throws Exception {
        return mvc.perform(json(post("/api/dashboards"), "{\"name\":\"%s\"}".formatted(name)).with(as(shop.owner(), shop.id())))
                .andReturn().getResponse().getStatus();
    }

    private int invite(String email) throws Exception {
        return mvc.perform(json(post("/api/businesses/%d/invitations".formatted(shop.id())),
                "{\"email\":\"%s\",\"role\":\"VIEWER\"}".formatted(email)).with(as(shop.owner(), shop.id())))
                .andReturn().getResponse().getStatus();
    }

    private ResultActions importCsv(String kind, String csv, boolean dryRun, String... params) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/imports/" + kind)
                .file(new MockMultipartFile("file", kind + ".csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)));
        request.param("dryRun", Boolean.toString(dryRun));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request.with(as(shop.owner(), shop.id())));
    }

    private static void assertPlanLimit(MvcResult result, String resource, int limit, long used, String plan,
            boolean upgrade) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(409);
        assertThat((String) read(body, "$.code")).isEqualTo("plan_limit");
        assertThat((String) read(body, "$.resource")).isEqualTo(resource);
        assertThat(((Number) read(body, "$.limit")).intValue()).isEqualTo(limit);
        assertThat(((Number) read(body, "$.used")).longValue()).isEqualTo(used);
        assertThat((String) read(body, "$.plan")).isEqualTo(plan);
        assertThat((Boolean) read(body, "$.upgradeAvailable")).isEqualTo(upgrade);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE business_id = ?", Long.class, shop.id());
    }

    private static long successes(List<Integer> statuses, int ok) {
        return statuses.stream().filter(s -> s == ok).count();
    }

    // ---------------------------------------------------------------- stores

    @Test
    void storesStopAtTheFreeLimitWithAPlanLimitProblem() throws Exception {
        assertThat(createStore("S1")).isEqualTo(201);
        assertThat(createStore("S2")).isEqualTo(201);
        MvcResult refused = mvc.perform(json(post("/api/stores"), "{\"code\":\"S3\",\"name\":\"Third\"}")
                .with(as(shop.owner(), shop.id()))).andReturn();
        assertPlanLimit(refused, "stores", 2, 2, "free", true);
        assertThat((String) read(body(refused), "$.detail"))
                .isEqualTo("The Free plan allows 2 stores. Upgrade to Pro or delete one first.");
        assertThat(count("stores")).isEqualTo(2);
    }

    @Test
    void parallelStoreCreationsNeverExceedTheLimit() throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String code = "P" + i;
            tasks.add(() -> createStore(code));
        }
        List<Integer> statuses = concurrently(tasks);
        assertThat(successes(statuses, 201)).isEqualTo(2);
        assertThat(successes(statuses, 409)).isEqualTo(6);
        assertThat(count("stores")).isEqualTo(2);
    }

    @Test
    void storeImportsThatWouldExceedTheLimitAreRefusedAsAWhole() throws Exception {
        assertThat(createStore("S1")).isEqualTo(201);
        String csv = "code,name,city\nA1,Alpha,Boston\nB1,Beta,Austin\nC1,Gamma,Denver\n";
        // A dry run reports it as a file error.
        importCsv("stores", csv, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].line").isEmpty())
                .andExpect(jsonPath("$.errors[0].message").value(
                        "This would add 3 stores; the Free plan allows 2 stores and the business has 1. "
                                + "Upgrade to Pro or delete some first."));
        // The real import is refused before writing anything, with the numbers.
        MvcResult refused = importCsv("stores", csv, false).andReturn();
        assertPlanLimit(refused, "stores", 2, 1, "free", true);
        assertThat(count("stores")).isEqualTo(1);
        assertThat(count("import_batches")).isZero();
        // One more store fits.
        importCsv("stores", "code,name,city\nA1,Alpha,Boston\n", false)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IMPORTED"));
        assertThat(count("stores")).isEqualTo(2);
        // Updating existing stores creates nothing: allowed at the limit.
        importCsv("stores", "code,name,city\nA1,Alpha Renamed,Boston\n", false, "mode", "create_or_update")
                .andExpect(jsonPath("$.status").value("IMPORTED"));
        MvcResult more = mvc.perform(multipart("/api/imports/stores")
                .file(new MockMultipartFile("file", "s.csv", "text/csv", "code,name,city\nZ9,Zed,Miami\n".getBytes(StandardCharsets.UTF_8)))
                .param("dryRun", "false").param("mode", "create_or_update")
                .with(as(shop.owner(), shop.id()))).andReturn();
        assertPlanLimit(more, "stores", 2, 2, "free", true);
    }

    // ---------------------------------------------------------------- members

    @Test
    void invitationsReserveSeatsAndStopAtTheLimit() throws Exception {
        assertThat(invite("a@x.test")).isEqualTo(201);
        assertThat(invite("b@x.test")).isEqualTo(201);
        // Re-inviting the same address replaces the invitation: no extra seat.
        assertThat(invite("b@x.test")).isEqualTo(201);
        MvcResult refused = mvc.perform(json(post("/api/businesses/%d/invitations".formatted(shop.id())),
                "{\"email\":\"c@x.test\",\"role\":\"VIEWER\"}").with(as(shop.owner(), shop.id()))).andReturn();
        assertPlanLimit(refused, "members", 3, 3, "free", true);
        assertThat((String) read(body(refused), "$.detail")).isEqualTo(
                "The Free plan allows 3 members (open invitations count). Upgrade to Pro or remove a member or revoke an invitation first.");
    }

    @Test
    void parallelInvitationsNeverExceedTheLimit() throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String email = "p" + i + "@x.test";
            tasks.add(() -> invite(email));
        }
        List<Integer> statuses = concurrently(tasks);
        assertThat(successes(statuses, 201)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invitations WHERE business_id = ? AND revoked_at IS NULL",
                Long.class, shop.id())).isEqualTo(2);
    }

    private String openInvitation(String email) throws Exception {
        String token = "token-" + email;
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("""
                INSERT INTO invitations (business_id, email, role, token_sha256, invited_by, expires_at)
                VALUES (?, ?, 'VIEWER', ?, ?, now() + interval '7 days')
                """, shop.id(), email, sha, shop.owner().id());
        return token;
    }

    private MvcResult accept(TestUser user, String token) throws Exception {
        return mvc.perform(json(post("/api/invitations/accept"), "{\"token\":\"%s\"}".formatted(token))
                .with(as(user)).with(com.oussamaksantini.insightstudio.testsupport.TestAccounts.csrf())).andReturn();
    }

    @Test
    void acceptingIsRefusedWhileTheMembersFillThePlan() throws Exception {
        // Invited while the business had room (or was on Pro); then the members filled the plan.
        String token = openInvitation("late@x.test");
        TestUser late = accounts.user("late@x.test");
        accounts.member("m1@x.test", shop.id(), Role.VIEWER);
        accounts.member("m2@x.test", shop.id(), Role.VIEWER);
        MvcResult refused = accept(late, token);
        assertPlanLimit(refused, "members", 3, 3, "free", true);
        assertThat((String) read(body(refused), "$.detail"))
                .isEqualTo("This business is full: the Free plan allows 3 members. Ask an owner to upgrade or remove a member first.");
        // The invitation still works once a seat is free.
        jdbc.update("DELETE FROM memberships WHERE business_id = ? AND role = 'VIEWER' AND user_id = (SELECT id FROM users WHERE email = 'm2@x.test')", shop.id());
        assertThat(accept(late, token).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void anInvitationsOwnSeatIsNotCountedTwiceWhenAccepting() throws Exception {
        accounts.member("m1@x.test", shop.id(), Role.VIEWER);
        String token = openInvitation("last@x.test");   // 2 members + 1 invitation: the plan is full
        assertThat(invite("extra@x.test")).isEqualTo(409);
        TestUser last = accounts.user("last@x.test");
        assertThat(accept(last, token).getResponse().getStatus()).isEqualTo(200);
        assertThat(count("memberships")).isEqualTo(3);
    }

    // ---------------------------------------------------------------- charts and dashboards

    @Test
    void chartsStopAtTheLimitIncludingDuplicatesAndParallelCreations() throws Exception {
        for (int i = 1; i <= 8; i++) {
            assertThat(createChart("Chart " + i)).isEqualTo(201);
        }
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String title = "Parallel " + i;
            tasks.add(() -> createChart(title));
        }
        assertThat(successes(concurrently(tasks), 201)).isEqualTo(2);
        assertThat(count("chart_definitions")).isEqualTo(10);
        long chartId = jdbc.queryForObject("SELECT MIN(id) FROM chart_definitions WHERE business_id = ?", Long.class, shop.id());
        MvcResult duplicate = mvc.perform(post("/api/charts/%d/duplicate".formatted(chartId)).with(as(shop.owner(), shop.id())))
                .andReturn();
        assertPlanLimit(duplicate, "charts", 10, 10, "free", true);
    }

    @Test
    void dashboardsStopAtTheLimitIncludingDuplicatesAndParallelCreations() throws Exception {
        assertThat(createDashboard("Board 1")).isEqualTo(201);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String name = "Parallel " + i;
            tasks.add(() -> createDashboard(name));
        }
        assertThat(successes(concurrently(tasks), 201)).isEqualTo(2);
        assertThat(count("dashboards")).isEqualTo(3);
        long id = jdbc.queryForObject("SELECT MIN(id) FROM dashboards WHERE business_id = ?", Long.class, shop.id());
        MvcResult duplicate = mvc.perform(post("/api/dashboards/%d/duplicate".formatted(id)).with(as(shop.owner(), shop.id())))
                .andReturn();
        assertPlanLimit(duplicate, "dashboards", 3, 3, "free", true);
    }

    // ---------------------------------------------------------------- imports per month

    private String products(int n) {
        return "sku,name,category,list_price\nSKU-%d,Product %d,Tops,10.00\n".formatted(n, n);
    }

    @Test
    void realImportsStopAtTheMonthlyLimitWhileRejectedImportsAndDryRunsDoNotCount() throws Exception {
        for (int i = 0; i < 9; i++) {
            fixture.importBatch(shop.id(), "old-" + i + ".csv", "0");
        }
        // Last month's imports and rejected ones don't count.
        long lastMonth = fixture.importBatch(shop.id(), "last-month.csv", "0");
        jdbc.update("UPDATE import_batches SET created_at = now() - interval '40 days' WHERE id = ?", lastMonth);
        long rejected = fixture.importBatch(shop.id(), "rejected.csv", "0");
        jdbc.update("UPDATE import_batches SET status = 'REJECTED' WHERE id = ?", rejected);
        // A dry run is fine and doesn't count.
        importCsv("products", products(0), true).andExpect(jsonPath("$.status").value("VALIDATED"));

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            String csv = products(i);
            tasks.add(() -> importCsv("products", csv, false).andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = concurrently(tasks);
        assertThat(successes(statuses, 200)).isEqualTo(1);
        assertThat(successes(statuses, 409)).isEqualTo(7);
        assertThat(count("products")).isEqualTo(1);

        MvcResult refused = importCsv("sales", "store_code,receipt_number,sold_at,sku,quantity,unit_price\n", false).andReturn();
        // An empty sales file is a 400 before anything else; a real one is refused by the plan.
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertPlanLimit(importCsv("products", products(20), false).andReturn(), "importsPerMonth", 10, 10, "free", true);
        // A dry run says it would be refused.
        importCsv("products", products(21), true)
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].message").value(
                        "The Free plan allows 10 imports per month. Upgrade to Pro or wait until next month."));
    }

    // ---------------------------------------------------------------- plans

    @Test
    void proRaisesTheLimitsAndADowngradeKeepsEverything() throws Exception {
        subscribe(shop);
        for (int i = 1; i <= 4; i++) {
            assertThat(createStore("S" + i)).isEqualTo(201);
        }
        for (int i = 1; i <= 4; i++) {
            assertThat(createDashboard("Board " + i)).isEqualTo(201);
        }
        assertThat(createChart("Kept chart")).isEqualTo(201);
        assertThat(invite("a@x.test")).isEqualTo(201);
        assertThat(invite("b@x.test")).isEqualTo(201);
        assertThat(invite("c@x.test")).isEqualTo(201);

        // Canceled now: back to Free with 4 stores, 4 dashboards, 4 seats taken.
        act(shop, portal(shop), "cancel-now");
        eventWorker.processDue();
        assertThat(planOf(shop)).isEqualTo("free");

        String billing = billing(shop);
        assertThat(((Number) read(billing, "$.usage[1].used")).intValue()).isEqualTo(4);
        assertThat(((Number) read(billing, "$.usage[1].limit")).intValue()).isEqualTo(2);

        // Everything is still there and readable.
        mvc.perform(get("/api/stores").with(as(shop.owner(), shop.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stores.length()").value(4));
        mvc.perform(get("/api/dashboards").with(as(shop.owner(), shop.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
        // Creating more is refused...
        assertPlanLimit(mvc.perform(json(post("/api/stores"), "{\"code\":\"S9\",\"name\":\"Nine\"}")
                .with(as(shop.owner(), shop.id()))).andReturn(), "stores", 2, 4, "free", true);
        assertPlanLimit(mvc.perform(json(post("/api/dashboards"), "{\"name\":\"More\"}")
                .with(as(shop.owner(), shop.id()))).andReturn(), "dashboards", 3, 4, "free", true);
        assertPlanLimit(mvc.perform(json(post("/api/businesses/%d/invitations".formatted(shop.id())),
                "{\"email\":\"d@x.test\",\"role\":\"VIEWER\"}").with(as(shop.owner(), shop.id()))).andReturn(),
                "members", 3, 4, "free", true);
        // ... but editing, deleting and exporting are not.
        long dashboard = jdbc.queryForObject("SELECT MIN(id) FROM dashboards WHERE business_id = ?", Long.class, shop.id());
        mvc.perform(json(put("/api/dashboards/" + dashboard), "{\"name\":\"Renamed\",\"layout\":{\"schemaVersion\":1,\"widgets\":[],\"desktop\":{\"columns\":12,\"items\":[]},\"mobile\":{\"columns\":4,\"items\":[]}},\"expectedRevision\":1}")
                .with(as(shop.owner(), shop.id()))).andExpect(status().isOk());
        long chart = jdbc.queryForObject("SELECT MIN(id) FROM chart_definitions WHERE business_id = ?", Long.class, shop.id());
        mvc.perform(json(put("/api/charts/" + chart), "{\"definition\":%s,\"expectedRevision\":1}".formatted(CHART.formatted("Edited")))
                .with(as(shop.owner(), shop.id()))).andExpect(status().isOk());
        mvc.perform(delete("/api/dashboards/" + dashboard).with(as(shop.owner(), shop.id()))).andExpect(status().isNoContent());
        mvc.perform(get("/api/businesses/%d/export".formatted(shop.id())).with(as(shop.owner(), shop.id())))
                .andExpect(status().isOk());
        assertThat(count("stores")).isEqualTo(4);
    }

    @Test
    void atAProLimitNoUpgradeIsOffered() throws Exception {
        subscribe(shop);
        for (int i = 0; i < 50; i++) {
            fixture.store(shop.id(), "B" + i, "Branch " + i, null);
        }
        MvcResult refused = mvc.perform(json(post("/api/stores"), "{\"code\":\"X\",\"name\":\"X\"}")
                .with(as(shop.owner(), shop.id()))).andReturn();
        assertPlanLimit(refused, "stores", 50, 50, "pro", false);
        assertThat((String) read(body(refused), "$.detail")).isEqualTo("The Pro plan allows 50 stores. Delete one first.");
    }
}
