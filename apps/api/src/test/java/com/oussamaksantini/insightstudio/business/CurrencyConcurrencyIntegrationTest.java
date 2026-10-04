package com.oussamaksantini.insightstudio.business;

import static com.oussamaksantini.insightstudio.testsupport.TestAccounts.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The currency can never change once a business holds amounts, even when the change races with a
 * product or sale being written (V17: one advisory lock per business, shared by every product and
 * sale insert, exclusive for a currency change, and a database guard). Each test holds one side open
 * in its own transaction and proves the other side waits, then sees the outcome.
 */
class CurrencyConcurrencyIntegrationTest extends PostgresIntegrationTest {

    /** Longer than any uncontended request here: a request still running after it is waiting on a lock. */
    private static final long BLOCKED_MS = 1500;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    SqlFixture db;
    long business;
    long store;
    TestUser owner;
    final ExecutorService pool = Executors.newFixedThreadPool(4);

    @BeforeEach
    void setUp() {
        db = new SqlFixture(jdbc);
        db.clear();
        business = db.business("Race Co", "race-co", "EUR", "UTC");
        store = db.store(business, "S1", "Main", "Paris");
        owner = new TestAccounts(jdbc).member("owner@race.co", business, Role.OWNER);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private Future<Integer> changeCurrency(String currency) {
        return pool.submit(() -> mvc.perform(patch("/api/businesses/" + business).with(as(owner, business))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"" + currency + "\"}"))
                .andReturn().getResponse().getStatus());
    }

    private Future<Integer> createProduct(String sku) {
        return pool.submit(() -> mvc.perform(post("/api/products").with(as(owner, business))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"name\":\"Tee\",\"category\":\"Tops\",\"listPrice\":20}"))
                .andReturn().getResponse().getStatus());
    }

    private Future<Integer> importProducts(String csvRows) {
        MockMultipartFile file = new MockMultipartFile("file", "products.csv", "text/csv",
                ("sku,name,category,list_price\n" + csvRows).getBytes(StandardCharsets.UTF_8));
        return pool.submit(() -> mvc.perform(multipart("/api/imports/products").file(file).param("dryRun", "false")
                .with(as(owner, business))).andReturn().getResponse().getStatus());
    }

    private static void assertStillWaiting(Future<?> request) throws Exception {
        assertThatThrownBy(() -> request.get(BLOCKED_MS, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
    }

    private static int result(Future<Integer> request) throws Exception {
        return request.get(30, TimeUnit.SECONDS);
    }

    private String currency() {
        return jdbc.queryForObject("SELECT currency FROM businesses WHERE id = ?", String.class, business);
    }

    private int currencyEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE business_id = ? AND action = 'business.currency_changed'",
                Integer.class, business);
    }

    private Connection openTransaction() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Test
    void aCurrencyChangeWaitsForAProductBeingWrittenThenRefuses() throws Exception {
        try (Connection writer = openTransaction()) {
            execute(writer, "INSERT INTO products (business_id, sku, name, category, list_price) VALUES (" + business
                    + ", 'TEE', 'Tee', 'Tops', 20)");
            Future<Integer> change = changeCurrency("USD");
            assertStillWaiting(change);
            writer.commit();
            assertThat(result(change)).isEqualTo(409);
        }
        assertThat(currency()).isEqualTo("EUR");
        assertThat(currencyEvents()).isZero();
    }

    @Test
    void aCurrencyChangeWaitsForASaleBeingWrittenThenRefuses() throws Exception {
        try (Connection writer = openTransaction()) {
            execute(writer, "INSERT INTO sales (store_id, receipt_number, sold_at) VALUES (" + store + ", 'R1', now())");
            Future<Integer> change = changeCurrency("USD");
            assertStillWaiting(change);
            writer.commit();
            assertThat(result(change)).isEqualTo(409);
        }
        assertThat(currency()).isEqualTo("EUR");
    }

    @Test
    void aWriterThatRollsBackLeavesTheCurrencyFreeToChange() throws Exception {
        try (Connection writer = openTransaction()) {
            execute(writer, "INSERT INTO products (business_id, sku, name, category, list_price) VALUES (" + business
                    + ", 'TEE', 'Tee', 'Tops', 20)");
            Future<Integer> change = changeCurrency("USD");
            assertStillWaiting(change);
            writer.rollback();
            assertThat(result(change)).isEqualTo(200);
        }
        assertThat(currency()).isEqualTo("USD");
        assertThat(currencyEvents()).isOne();
    }

    @Test
    void writersWaitForACurrencyChangeInProgressAndThenWriteInTheNewCurrency() throws Exception {
        Future<Integer> imported;
        Future<Integer> created;
        try (Connection changer = openTransaction()) {
            execute(changer, "UPDATE businesses SET currency = 'USD' WHERE id = " + business);
            imported = importProducts("TEE-1,Tee,Tops,20\nTEE-2,Tee,Tops,22\n");
            created = createProduct("CAP-1");
            assertStillWaiting(imported);
            assertStillWaiting(created);
            changer.commit();
        }
        assertThat(result(imported)).isEqualTo(200);
        assertThat(result(created)).isEqualTo(201);
        assertThat(currency()).isEqualTo("USD");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE business_id = ?", Integer.class, business)).isEqualTo(3);
        // From now on the currency is locked.
        assertThat(result(changeCurrency("GBP"))).isEqualTo(409);
        assertThat(currency()).isEqualTo("USD");
    }

    /**
     * A writer that already holds the business row's KEY SHARE (it inserted a store: a foreign key
     * check) must not deadlock with a currency change: the change does not wait for it, and the
     * writer's products then go in after the change.
     */
    @Test
    void noDeadlockWithAWriterHoldingTheBusinessRowsKeyShare() throws Exception {
        try (Connection writer = openTransaction()) {
            execute(writer, "INSERT INTO stores (business_id, code, name, city) VALUES (" + business + ", 'S2', 'Second', 'Lyon')");
            assertThat(result(changeCurrency("USD"))).isEqualTo(200);
            execute(writer, "INSERT INTO products (business_id, sku, name, category, list_price) VALUES (" + business
                    + ", 'TEE', 'Tee', 'Tops', 20)");
            writer.commit();
        }
        assertThat(currency()).isEqualTo("USD");
        assertThat(result(changeCurrency("GBP"))).isEqualTo(409);
    }

    @Test
    void theDatabaseRefusesACurrencyChangeOnAnyPathOnceThereAreAmounts() {
        jdbc.update("UPDATE businesses SET currency = 'USD' WHERE id = ?", business);   // no amounts yet: allowed
        db.product(business, "TEE", "Tee", "Tops", "20.00");
        assertThatThrownBy(() -> jdbc.update("UPDATE businesses SET currency = 'GBP' WHERE id = ?", business))
                .satisfies(e -> assertThat(BusinessQueries.isCurrencyLocked(e)).isTrue());
        // Other settings, and "changing" to the same currency, still work.
        jdbc.update("UPDATE businesses SET name = 'Race Co 2', currency = 'USD' WHERE id = ?", business);
        assertThat(currency()).isEqualTo("USD");
    }

    /** Many simultaneous currency changes and product creations: never an error, never relabelled money. */
    @Test
    void racingChangesAndWritesStayConsistent() throws Exception {
        for (int round = 0; round < 15; round++) {
            jdbc.update("DELETE FROM products WHERE business_id = ?", business);
            jdbc.update("UPDATE businesses SET currency = 'EUR' WHERE id = ?", business);
            CyclicBarrier start = new CyclicBarrier(2);
            String target = round % 2 == 0 ? "USD" : "GBP";
            Future<Integer> change = pool.submit(() -> {
                start.await();
                return mvc.perform(patch("/api/businesses/" + business).with(as(owner, business))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"" + target + "\"}"))
                        .andReturn().getResponse().getStatus();
            });
            String sku = "R" + round;
            Future<Integer> write = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/products").with(as(owner, business)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"name\":\"Tee\",\"category\":\"Tops\",\"listPrice\":20}"))
                        .andReturn().getResponse().getStatus();
            });
            List<Integer> statuses = new ArrayList<>(List.of(result(change), result(write)));
            assertThat(statuses.get(1)).as("round %d product", round).isEqualTo(201);
            assertThat(statuses.get(0)).as("round %d currency", round).isIn(200, 409);
            // 200: the change committed before the product was written (it waited, then wrote in the new
            // currency); 409: the product committed first, and the currency did not move.
            assertThat(currency()).isEqualTo(statuses.get(0) == 200 ? target : "EUR");
        }
    }
}
