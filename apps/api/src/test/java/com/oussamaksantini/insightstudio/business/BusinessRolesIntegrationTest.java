package com.oussamaksantini.insightstudio.business;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The role matrix of docs/accounts-contract.md §4, business creation and catalog setup. */
class BusinessRolesIntegrationTest extends PostgresIntegrationTest {

    private static final String CSV = """
            store_code,receipt_number,sold_at,sku,quantity,unit_price
            S1,R-1,2026-06-05T10:00:00Z,SKU-1,1,5.00
            """;

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
    TestUser outsider;

    @BeforeEach
    void loadFixture() {
        db = new SqlFixture(jdbc);
        db.clear();
        accounts = new TestAccounts(jdbc);
        business = db.business("Test Co", "test-co", "EUR", "UTC");
        db.store(business, "S1", "Store", null);
        db.product(business, "SKU-1", "Widget", "Misc", "5.00");
        owner = accounts.member("owner@test.co", business, Role.OWNER);
        admin = accounts.member("admin@test.co", business, Role.ADMIN);
        viewer = accounts.member("viewer@test.co", business, Role.VIEWER);
        outsider = accounts.user("outsider@test.co");
    }

    private ResultActions json(MockHttpServletRequestBuilder request, TestUser user, String body) throws Exception {
        return mvc.perform(request.with(as(user, business)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String members() {
        return "/api/businesses/" + business + "/members";
    }

    private Role roleOf(TestUser user) {
        return jdbc.queryForList("SELECT role FROM memberships WHERE user_id = ? AND business_id = ?", String.class,
                user.id(), business).stream().findFirst().map(Role::valueOf).orElse(null);
    }

    @Nested
    class Viewer {

        @Test
        void readsButCannotChangeAnything() throws Exception {
            mvc.perform(get("/api/dashboard/context").with(as(viewer, business)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.access.role").value("VIEWER"))
                    .andExpect(jsonPath("$.access.canImport").value(false))
                    .andExpect(jsonPath("$.access.canManageCatalog").value(false))
                    .andExpect(jsonPath("$.access.canManageMembers").value(false))
                    .andExpect(jsonPath("$.access.readOnly").value(true));

            MockMultipartFile file = new MockMultipartFile("file", "x.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8));
            mvc.perform(multipart("/api/imports").file(file).with(as(viewer, business)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value("You need the ADMIN role for this."));
            mvc.perform(get("/api/imports").with(as(viewer, business))).andExpect(status().isForbidden());
            json(post("/api/stores"), viewer, "{\"code\":\"S2\",\"name\":\"N\"}").andExpect(status().isForbidden());
            json(post("/api/products"), viewer, "{\"sku\":\"P2\",\"name\":\"N\",\"category\":\"C\",\"listPrice\":1}")
                    .andExpect(status().isForbidden());
            mvc.perform(get(members()).with(as(viewer, business))).andExpect(status().isForbidden());
            json(post(members()), viewer, "{\"email\":\"outsider@test.co\",\"role\":\"VIEWER\"}").andExpect(status().isForbidden());
            json(patch("/api/businesses/" + business), viewer, "{\"name\":\"New\"}").andExpect(status().isForbidden());
            mvc.perform(delete(members() + "/" + admin.id()).with(as(viewer, business))).andExpect(status().isForbidden());

            assertThat(db.count("stores")).isEqualTo(1);
            assertThat(db.count("products")).isEqualTo(1);
            assertThat(db.count("sales")).isZero();
            assertThat(db.count("memberships")).isEqualTo(3);
        }

        @Test
        void canLeave() throws Exception {
            mvc.perform(delete(members() + "/" + viewer.id()).with(as(viewer, business))).andExpect(status().isNoContent());
            assertThat(roleOf(viewer)).isNull();
            mvc.perform(get("/api/dashboard/context").with(as(viewer, business))).andExpect(status().isNotFound());
        }
    }

    @Nested
    class Admin {

        @Test
        void managesCatalogImportsAndViewers() throws Exception {
            mvc.perform(get("/api/dashboard/context").with(as(admin, business)))
                    .andExpect(jsonPath("$.access.role").value("ADMIN"))
                    .andExpect(jsonPath("$.access.canImport").value(true))
                    .andExpect(jsonPath("$.access.readOnly").value(false));
            MockMultipartFile file = new MockMultipartFile("file", "x.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8));
            mvc.perform(multipart("/api/imports").file(file).param("dryRun", "false").with(as(admin, business)))
                    .andExpect(jsonPath("$.status").value("IMPORTED"));
            json(post("/api/stores"), admin, "{\"code\":\"S2\",\"name\":\"Second\",\"city\":\" Lyon \"}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("S2"))
                    .andExpect(jsonPath("$.city").value("Lyon"));
            json(post("/api/products"), admin, "{\"sku\":\"P2\",\"name\":\"Jacket\",\"category\":\"Outerwear\",\"listPrice\":99.5}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.listPrice").value(99.50));

            mvc.perform(get(members()).with(as(admin, business)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].email", contains("owner@test.co", "admin@test.co", "viewer@test.co")))
                    .andExpect(jsonPath("$[0].role").value("OWNER"))
                    .andExpect(jsonPath("$[0].since").isNotEmpty());

            json(post(members()), admin, "{\"email\":\"OUTSIDER@test.co\",\"role\":\"viewer\"}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.userId").value(outsider.id()))
                    .andExpect(jsonPath("$.role").value("VIEWER"));
            mvc.perform(delete(members() + "/" + outsider.id()).with(as(admin, business))).andExpect(status().isNoContent());
            json(post(members()), admin, "{\"email\":\"outsider@test.co\",\"role\":\"ADMIN\"}").andExpect(status().isCreated());
        }

        @Test
        void cannotActAsOwner() throws Exception {
            json(post(members()), admin, "{\"email\":\"outsider@test.co\",\"role\":\"OWNER\"}").andExpect(status().isForbidden());
            json(patch(members() + "/" + viewer.id()), admin, "{\"role\":\"ADMIN\"}").andExpect(status().isForbidden());
            json(patch("/api/businesses/" + business), admin, "{\"name\":\"New\"}").andExpect(status().isForbidden());
            TestUser admin2 = accounts.member("admin2@test.co", business, Role.ADMIN);
            mvc.perform(delete(members() + "/" + admin2.id()).with(as(admin, business)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value("Admins can only remove viewers."));
            mvc.perform(delete(members() + "/" + owner.id()).with(as(admin, business))).andExpect(status().isForbidden());
            assertThat(roleOf(outsider)).isNull();
            assertThat(roleOf(viewer)).isEqualTo(Role.VIEWER);
            assertThat(roleOf(admin2)).isEqualTo(Role.ADMIN);
            assertThat(roleOf(owner)).isEqualTo(Role.OWNER);
            // Leaving is always allowed for an admin.
            mvc.perform(delete(members() + "/" + admin.id()).with(as(admin, business))).andExpect(status().isNoContent());
        }
    }

    @Nested
    class Owner {

        @Test
        void managesMembersAndBusiness() throws Exception {
            json(post(members()), owner, "{\"email\":\"outsider@test.co\",\"role\":\"OWNER\"}").andExpect(status().isCreated());
            json(patch(members() + "/" + viewer.id()), owner, "{\"role\":\"ADMIN\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("ADMIN"));
            mvc.perform(delete(members() + "/" + admin.id()).with(as(owner, business))).andExpect(status().isNoContent());
            // Two owners: one may be removed.
            mvc.perform(delete(members() + "/" + outsider.id()).with(as(owner, business))).andExpect(status().isNoContent());
            json(patch("/api/businesses/" + business), owner, "{\"name\":\" Renamed Co \",\"timeZone\":\"America/New_York\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Renamed Co"))
                    .andExpect(jsonPath("$.slug").value("test-co"))
                    .andExpect(jsonPath("$.timeZone").value("America/New_York"));
            json(patch("/api/businesses/" + business), owner, "{\"timeZone\":\"Mars/Base\"}").andExpect(status().isBadRequest());
            json(patch("/api/businesses/" + business), owner, "{}").andExpect(status().isBadRequest());
        }

        @Test
        void lastOwnerIsProtected() throws Exception {
            json(patch(members() + "/" + owner.id()), owner, "{\"role\":\"ADMIN\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("A business needs at least one owner."));
            mvc.perform(delete(members() + "/" + owner.id()).with(as(owner, business))).andExpect(status().isConflict());
            assertThat(roleOf(owner)).isEqualTo(Role.OWNER);

            // With a second owner, the first may step down or leave.
            json(patch(members() + "/" + admin.id()), owner, "{\"role\":\"OWNER\"}").andExpect(status().isOk());
            json(patch(members() + "/" + owner.id()), owner, "{\"role\":\"VIEWER\"}").andExpect(status().isOk());
            assertThat(roleOf(owner)).isEqualTo(Role.VIEWER);
            mvc.perform(delete(members() + "/" + admin.id()).with(as(admin, business))).andExpect(status().isConflict());
        }

        @Test
        void memberInputErrors() throws Exception {
            json(post(members()), owner, "{\"email\":\"ghost@test.co\",\"role\":\"VIEWER\"}")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("No account with that email."));
            json(post(members()), owner, "{\"email\":\"viewer@test.co\",\"role\":\"VIEWER\"}").andExpect(status().isConflict());
            json(post(members()), owner, "{\"email\":\"outsider@test.co\",\"role\":\"GOD\"}").andExpect(status().isBadRequest());
            json(post(members()), owner, "{\"email\":\"\",\"role\":\"VIEWER\"}").andExpect(status().isBadRequest());
            json(patch(members() + "/" + outsider.id()), owner, "{\"role\":\"VIEWER\"}").andExpect(status().isNotFound());
            mvc.perform(delete(members() + "/999999").with(as(owner, business))).andExpect(status().isNotFound());
        }
    }

    @Nested
    class Businesses {

        @Test
        void anyoneSignedInCanCreateABusinessAndOwnsIt() throws Exception {
            mvc.perform(post("/api/businesses").with(as(outsider, business)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Café Crème\",\"currency\":\"EUR\",\"timeZone\":\"Europe/Paris\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.slug").value("cafe-creme"))
                    .andExpect(jsonPath("$.role").value("OWNER"));
            mvc.perform(post("/api/businesses").with(as(outsider, business)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Cafe creme\",\"currency\":\"EUR\",\"timeZone\":\"Europe/Paris\"}"))
                    .andExpect(jsonPath("$.slug").value("cafe-creme-2"));
            mvc.perform(get("/api/businesses").with(TestAccounts.as(outsider)))
                    .andExpect(jsonPath("$[*].slug", org.hamcrest.Matchers.containsInAnyOrder("cafe-creme", "cafe-creme-2")));
        }

        @Test
        void theDemoSlugIsReserved() throws Exception {
            mvc.perform(post("/api/businesses").with(as(outsider, business)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Fieldstone Apparel\",\"currency\":\"USD\",\"timeZone\":\"UTC\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.slug").value("fieldstone-apparel-2"));
        }

        @Test
        void validatesInput() throws Exception {
            for (String body : new String[] {
                "{\"name\":\"\",\"currency\":\"EUR\",\"timeZone\":\"UTC\"}",
                "{\"name\":\"X\",\"currency\":\"EURO\",\"timeZone\":\"UTC\"}",
                "{\"name\":\"X\",\"currency\":\"XYZ\",\"timeZone\":\"UTC\"}",
                "{\"name\":\"X\",\"currency\":\"EUR\",\"timeZone\":\"+02:00\"}",
                "{\"name\":\"X\",\"currency\":\"EUR\"}",
                "not json",
            }) {
                mvc.perform(post("/api/businesses").with(as(outsider, business)).contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andExpect(status().isBadRequest());
            }
            assertThat(db.count("businesses")).isEqualTo(1);
        }
    }

    @Nested
    class Catalog {

        @Test
        void duplicatesAndValidation() throws Exception {
            json(post("/api/stores"), owner, "{\"code\":\"S1\",\"name\":\"Again\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("A store with code 'S1' already exists."));
            json(post("/api/products"), owner, "{\"sku\":\"SKU-1\",\"name\":\"Again\",\"category\":\"C\",\"listPrice\":1}")
                    .andExpect(status().isConflict());
            for (String body : new String[] {
                "{\"code\":\"\",\"name\":\"N\"}", "{\"code\":\"has space\",\"name\":\"N\"}", "{\"code\":\"OK\"}",
                "{\"code\":\"" + "X".repeat(51) + "\",\"name\":\"N\"}",
            }) {
                json(post("/api/stores"), owner, body).andExpect(status().isBadRequest());
            }
            for (String body : new String[] {
                "{\"sku\":\"P\",\"name\":\"N\",\"category\":\"C\",\"listPrice\":-1}",
                "{\"sku\":\"P\",\"name\":\"N\",\"category\":\"C\",\"listPrice\":1.234}",
                "{\"sku\":\"P\",\"name\":\"N\",\"category\":\"C\"}",
                "{\"sku\":\"P\",\"name\":\"N\",\"listPrice\":1}",
                "{\"sku\":\"P,Q\",\"name\":\"N\",\"category\":\"C\",\"listPrice\":1}",
            }) {
                json(post("/api/products"), owner, body).andExpect(status().isBadRequest());
            }
            assertThat(db.count("stores")).isEqualTo(1);
            assertThat(db.count("products")).isEqualTo(1);
        }

        @Test
        void createdItemsAppearInReads() throws Exception {
            json(post("/api/stores"), owner, "{\"code\":\"NEW\",\"name\":\"New Store\"}").andExpect(status().isCreated());
            mvc.perform(get("/api/dashboard/context").with(as(viewer, business)))
                    .andExpect(jsonPath("$.stores[*].code", contains("NEW", "S1")));
        }
    }
}
