package com.oussamaksantini.insightstudio.chart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The chart figures against an <b>independent</b> hand-written SQL in this test (not the API's query
 * code): every metric for every grouping (time by day, week and month, store, product, category,
 * none), with filters, over windows around both DST changes and month ends of a business in
 * Europe/Paris, plus equality with the report API where they overlap (monthly report rows and
 * totals, category report rows and totals).
 *
 * <p>The independent SQL filters and buckets by the sale's <b>local date</b>
 * ({@code (sold_at AT TIME ZONE b.time_zone)::date}) and inlines the filter lists, while the API binds
 * instants and parameters, so the two do not share a mistake. A second business with sales on the
 * same days must never count.
 */
class ChartMetricsIntegrationTest extends PostgresIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc owner;
    long business;
    long alpha;
    long bravo;
    long charlie;
    long edge;
    long jacket;
    long tee;
    long scarf;
    long boots;

    record Window(String name, String from, String to) {
    }

    private static final List<Window> WINDOWS = List.of(
            new Window("spring DST", "2026-03-25", "2026-04-05"),
            new Window("autumn DST", "2026-10-20", "2026-11-03"),
            new Window("whole year", "2026-01-01", "2026-12-31"),
            new Window("partial months", "2026-02-11", "2026-05-17"),
            new Window("no sales", "2025-01-01", "2025-01-31"));

    @BeforeEach
    void loadFixture() {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();
        business = db.business("Paris Co", "paris-co", "EUR", "Europe/Paris");
        alpha = db.store(business, "A", "Alpha", "Paris");
        bravo = db.store(business, "B", "bravo", "Lyon");
        charlie = db.store(business, "C", "Charlie (no sales)", "Nice");
        edge = db.store(business, "E", "Edge cases", "Paris");
        jacket = db.product(business, "P1", "Jacket", "Outerwear", "120.00");
        tee = db.product(business, "P2", "Tee", "Tops", "20.00");
        scarf = db.product(business, "P3", "Scarf", "Tops", "35.00");
        boots = db.product(business, "P4", "Boots", "Footwear", "99.90");
        long socks = db.product(business, "P5", "Socks", "footwear", "5.00");
        db.product(business, "P6", "Unsold belt", "Accessories", "15.00");
        List<long[]> products = List.of(
                new long[] {jacket, 12000}, new long[] {tee, 2000}, new long[] {scarf, 3500}, new long[] {boots, 9990},
                new long[] {socks, 500});

        Random random = new Random(20261002);
        long start = Instant.parse("2025-12-31T00:00:00Z").getEpochSecond();
        long end = Instant.parse("2027-01-01T12:00:00Z").getEpochSecond();
        for (int i = 0; i < 320; i++) {
            Instant soldAt = Instant.ofEpochSecond(start + (long) (random.nextDouble() * (end - start)));
            db.sale(random.nextBoolean() ? alpha : bravo, "R-" + i, soldAt.toString(), lines(random, products));
        }
        // Store "Edge cases": one Tee at 10.00 per receipt, at local midnights, month ends and both DST
        // changes of 2026 in Paris (spring 2026-03-29, autumn 2026-10-25).
        for (String instant : List.of(
                "2026-03-28T22:59:00Z", // Sat Mar 28 23:59 CET
                "2026-03-28T23:00:00Z", // Sun Mar 29 00:00 CET
                "2026-03-29T21:59:00Z", // Sun Mar 29 23:59 CEST
                "2026-03-29T22:00:00Z", // Mon Mar 30 00:00 CEST (a new ISO week)
                "2026-03-31T21:59:00Z", // Tue Mar 31 23:59 CEST
                "2026-03-31T22:00:00Z", // Wed Apr 1 00:00 CEST
                "2026-10-25T00:30:00Z", // Sun Oct 25 02:30 CEST
                "2026-10-25T01:30:00Z", // Sun Oct 25 02:30 CET
                "2026-10-25T22:59:00Z", // Sun Oct 25 23:59 CET
                "2026-10-25T23:00:00Z")) { // Mon Oct 26 00:00 CET
            db.sale(edge, "E-" + instant, instant, tee, 1, "10.00");
        }
        db.sale(alpha, "NO-ITEMS", "2026-03-30T10:00:00Z");

        long other = db.business("Other Co", "other-co", "EUR", "Europe/Paris");
        long otherStore = db.store(other, "X", "Other", "Paris");
        long otherProduct = db.product(other, "X1", "Other tee", "Tops", "20.00");
        for (int i = 0; i < 40; i++) {
            db.sale(otherStore, "X-" + i, Instant.parse("2026-01-01T12:00:00Z").plusSeconds(i * 777_777L).toString(),
                    otherProduct, 3, "777.77");
        }
        owner = TestAccounts.ownerMvc(context, jdbc, business);
    }

    /** One to three lines: list price, a discount, or free, so one product sells at several prices. */
    private static Object[] lines(Random random, List<long[]> products) {
        List<long[]> shuffled = new ArrayList<>(products);
        Collections.shuffle(shuffled, random);
        int count = 1 + random.nextInt(3);
        Object[] lines = new Object[count * 3];
        for (int i = 0; i < count; i++) {
            long[] product = shuffled.get(i);
            long cents = switch (random.nextInt(8)) {
                case 0 -> 0;
                case 1, 2 -> product[1] * 80 / 100;
                default -> product[1];
            };
            lines[i * 3] = product[0];
            lines[i * 3 + 1] = 1 + random.nextInt(4);
            lines[i * 3 + 2] = BigDecimal.valueOf(cents, 2).toPlainString();
        }
        return lines;
    }

    // ---------------------------------------------------------------- the API

    /** A table chart: all metrics allowed for the grouping, every group shown (limit 50). */
    private static String table(String groupBy, String granularity, Window window, String filters) {
        boolean byItem = groupBy.equals("product") || groupBy.equals("category");
        String metrics = byItem ? "[\"revenue\", \"orders\", \"units\"]"
                : "[\"revenue\", \"orders\", \"units\", \"average_order_value\"]";
        boolean ranked = byItem || groupBy.equals("store");
        return """
                {"title": "Check", "visualization": "table", "metrics": %s, "groupBy": "%s", "granularity": %s,
                 "range": {"type": "fixed", "from": "%s", "to": "%s"}, "filters": %s, "limit": %s}
                """.formatted(metrics, groupBy, granularity == null ? "null" : "\"" + granularity + "\"",
                window.from(), window.to(), filters, ranked ? "50" : "null");
    }

    private JsonNode preview(String definition) throws Exception {
        String body = owner.perform(post("/api/charts/preview").contentType(MediaType.APPLICATION_JSON).content(definition))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }

    private JsonNode getJson(String path) throws Exception {
        return JSON.readTree(owner.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- the independent SQL

    /** Revenue (scale 2), distinct orders and units. */
    record Totals(BigDecimal revenue, long orders, long units) {

        static final Totals ZERO = new Totals(new BigDecimal("0.00"), 0, 0);

        BigDecimal averageOrderValue() {
            return orders == 0 ? new BigDecimal("0.00")
                    : revenue.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP);
        }
    }

    /** Filters as the test SQL applies them (literal lists; this is test code with fixed values). */
    record Filters(List<Long> storeIds, List<String> categories, List<Long> productIds) {

        static final Filters NONE = new Filters(List.of(), List.of(), List.of());

        String json() {
            return "{\"storeIds\": %s, \"categories\": %s, \"productIds\": %s}".formatted(storeIds,
                    categories.stream().map(c -> "\"" + c + "\"").toList(), productIds);
        }

        String sql() {
            StringBuilder sql = new StringBuilder();
            if (!storeIds.isEmpty()) {
                sql.append(" AND st.id IN (").append(join(storeIds)).append(")");
            }
            if (!productIds.isEmpty()) {
                sql.append(" AND p.id IN (").append(join(productIds)).append(")");
            }
            if (!categories.isEmpty()) {
                sql.append(" AND p.category IN (")
                        .append(categories.stream().map(c -> "'" + c + "'").collect(Collectors.joining(", "))).append(")");
            }
            return sql.toString();
        }

        private static String join(List<Long> ids) {
            return ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
    }

    private static final String LOCAL_DATE = "CAST(s.sold_at AT TIME ZONE b.time_zone AS date)";

    /** Figures per group key, computed from the business's line items by local date. */
    private Map<String, Totals> expected(String groupBy, String granularity, Window window, Filters filters) {
        String key = switch (groupBy) {
            case "none" -> "'total'";
            case "time" -> "to_char(date_trunc('%s', %s), 'YYYY-MM-DD')".formatted(granularity, LOCAL_DATE);
            case "store" -> "CAST(st.id AS text)";
            case "product" -> "CAST(p.id AS text)";
            case "category" -> "p.category";
            default -> throw new IllegalArgumentException(groupBy);
        };
        String sql = """
                SELECT %s AS k,
                       SUM(si.quantity * si.unit_price) AS revenue,
                       COUNT(DISTINCT s.id) AS orders,
                       SUM(si.quantity) AS units
                FROM businesses b
                JOIN stores st ON st.business_id = b.id
                JOIN sales s ON s.store_id = st.id
                JOIN sale_items si ON si.sale_id = s.id
                JOIN products p ON p.id = si.product_id
                WHERE b.id = ? AND %s BETWEEN CAST(? AS date) AND CAST(? AS date)%s
                GROUP BY 1
                """.formatted(key, LOCAL_DATE, filters.sql());
        Map<String, Totals> groups = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            groups.put(rs.getString("k"), new Totals(rs.getBigDecimal("revenue").setScale(2, RoundingMode.HALF_UP),
                    rs.getLong("orders"), rs.getLong("units")));
        }, business, window.from(), window.to());
        return groups;
    }

    private Totals expectedTotal(Window window, Filters filters) {
        return expected("none", null, window, filters).getOrDefault("total", Totals.ZERO);
    }

    // ---------------------------------------------------------------- checks

    @Test
    void everyMetricOfEveryGroupingMatchesTheIndependentSql() throws Exception {
        List<Filters> filterSets = List.of(
                Filters.NONE,
                new Filters(List.of(alpha), List.of(), List.of()),
                new Filters(List.of(), List.of("Tops"), List.of()),
                new Filters(List.of(), List.of(), List.of(jacket, tee)),
                new Filters(List.of(bravo, edge), List.of("Tops", "Footwear"), List.of()),
                new Filters(List.of(alpha), List.of("Tops"), List.of(tee, boots)));
        List<String[]> groupings = List.of(new String[] {"none", null}, new String[] {"time", "day"},
                new String[] {"time", "week"}, new String[] {"time", "month"}, new String[] {"store", null},
                new String[] {"product", null}, new String[] {"category", null});
        SoftAssertions softly = new SoftAssertions();
        int checked = 0;
        for (Window window : WINDOWS) {
            for (Filters filters : filterSets) {
                for (String[] grouping : groupings) {
                    String label = "%s / %s %s / %s".formatted(window.name(), grouping[0], grouping[1], filters.json());
                    JsonNode result = preview(table(grouping[0], grouping[1], window, filters.json()));
                    check(softly, label, result, grouping[0], grouping[1], window, filters);
                    checked++;
                }
            }
        }
        softly.assertAll();
        assertThat(checked).isEqualTo(WINDOWS.size() * 6 * 7);
        // The fixture is not trivial: hundreds of orders, and filters that keep some of them.
        assertThat(expectedTotal(WINDOWS.get(2), Filters.NONE).orders()).isGreaterThan(300);
        assertThat(expectedTotal(WINDOWS.get(2), filterSets.get(5)).orders()).isBetween(5L, 100L);
    }

    private void check(SoftAssertions softly, String label, JsonNode result, String groupBy, String granularity,
            Window window, Filters filters) {
        Map<String, Totals> expected = expected(groupBy, granularity, window, filters);
        Totals total = expectedTotal(window, filters);
        softly.assertThat(result.get("engine").asString()).as(label).isEqualTo("sql");
        softly.assertThat(result.get("period").get("from").asString()).as(label).isEqualTo(window.from());
        softly.assertThat(result.get("period").get("to").asString()).as(label).isEqualTo(window.to());
        softly.assertThat(result.get("groupBy").asString()).as(label).isEqualTo(groupBy);
        checkValues(softly, label + " totals", result.get("totals"), total);

        List<String> keys = new ArrayList<>();
        result.get("rows").forEach(row -> keys.add(row.get("key").asString()));
        switch (groupBy) {
            case "none" -> {
                softly.assertThat(keys).as(label).containsExactly("total");
                checkValues(softly, label + " row", result.get("rows").get(0), total);
            }
            case "time" -> {
                List<LocalDate> buckets = buckets(LocalDate.parse(window.from()), LocalDate.parse(window.to()), granularity);
                softly.assertThat(keys).as(label + " buckets").containsExactlyElementsOf(
                        buckets.stream().map(LocalDate::toString).toList());
                softly.assertThat(keys).as(label + " buckets with sales").containsAll(expected.keySet());
                for (JsonNode row : result.get("rows")) {
                    LocalDate start = LocalDate.parse(row.get("key").asString());
                    checkValues(softly, label + " " + start, row, expected.getOrDefault(start.toString(), Totals.ZERO));
                    boolean partial = start.isBefore(LocalDate.parse(window.from()))
                            || next(start, granularity).minusDays(1).isAfter(LocalDate.parse(window.to()));
                    softly.assertThat(row.get("partial").asBoolean()).as(label + " partial " + start).isEqualTo(partial);
                }
                softly.assertThat(result.get("totalGroups").asInt()).as(label).isEqualTo(buckets.size());
            }
            default -> {
                Set<String> candidates = candidates(groupBy, filters, expected);
                softly.assertThat(new LinkedHashSet<>(keys)).as(label + " groups").isEqualTo(candidates);
                softly.assertThat(result.get("totalGroups").asInt()).as(label).isEqualTo(candidates.size());
                softly.assertThat(result.get("truncated").asBoolean()).as(label).isFalse();
                BigDecimal previous = null;
                for (JsonNode row : result.get("rows")) {
                    String key = row.get("key").asString();
                    checkValues(softly, label + " " + key, row, expected.getOrDefault(key, Totals.ZERO));
                    BigDecimal revenue = row.get("values").get("revenue").decimalValue();
                    if (previous != null) {
                        softly.assertThat(revenue).as(label + " ranked by revenue").isLessThanOrEqualTo(previous);
                    }
                    previous = revenue;
                }
            }
        }
    }

    /** Asserts every metric present in {@code node} (a row's {@code values}, or the totals). */
    private static void checkValues(SoftAssertions softly, String label, JsonNode node, Totals want) {
        JsonNode values = node.has("values") ? node.get("values") : node;
        softly.assertThat(values.get("revenue").decimalValue()).as(label + " revenue").isEqualByComparingTo(want.revenue());
        softly.assertThat(values.get("orders").asLong()).as(label + " orders").isEqualTo(want.orders());
        softly.assertThat(values.get("units").asLong()).as(label + " units").isEqualTo(want.units());
        if (values.has("average_order_value")) {
            softly.assertThat(values.get("average_order_value").decimalValue()).as(label + " AOV")
                    .isEqualByComparingTo(want.averageOrderValue());
        }
    }

    /** Groups a ranked chart must list: stores and catalogue categories allowed by the filters, products sold. */
    private Set<String> candidates(String groupBy, Filters filters, Map<String, Totals> expected) {
        Set<String> keys = new LinkedHashSet<>();
        switch (groupBy) {
            case "store" -> jdbc.queryForList("SELECT CAST(id AS text) FROM stores WHERE business_id = ?" + (filters.storeIds()
                    .isEmpty() ? "" : " AND id IN (" + Filters.join(filters.storeIds()) + ")"), String.class, business)
                    .forEach(keys::add);
            case "category" -> {
                String sql = "SELECT DISTINCT category FROM products p WHERE business_id = ?";
                if (!filters.categories().isEmpty()) {
                    sql += " AND p.category IN (" + filters.categories().stream().map(c -> "'" + c + "'")
                            .collect(Collectors.joining(", ")) + ")";
                }
                if (!filters.productIds().isEmpty()) {
                    sql += " AND p.category IN (SELECT category FROM products WHERE id IN ("
                            + Filters.join(filters.productIds()) + "))";
                }
                keys.addAll(jdbc.queryForList(sql, String.class, business));
            }
            default -> keys.addAll(expected.keySet());
        }
        return keys;
    }

    private static List<LocalDate> buckets(LocalDate from, LocalDate to, String granularity) {
        List<LocalDate> starts = new ArrayList<>();
        for (LocalDate d = truncate(from, granularity); !d.isAfter(to); d = next(d, granularity)) {
            starts.add(d);
        }
        return starts;
    }

    private static LocalDate truncate(LocalDate date, String granularity) {
        return switch (granularity) {
            case "day" -> date;
            case "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            default -> date.withDayOfMonth(1);
        };
    }

    private static LocalDate next(LocalDate start, String granularity) {
        return switch (granularity) {
            case "day" -> start.plusDays(1);
            case "week" -> start.plusWeeks(1);
            default -> start.plusMonths(1);
        };
    }

    @Test
    void localDaysWeeksAndMonthsAcrossDstAndMonthEnds() throws Exception {
        String edgeOnly = new Filters(List.of(edge), List.of(), List.of()).json();
        JsonNode days = preview(table("time", "day", new Window("", "2026-03-28", "2026-03-31"), edgeOnly));
        assertThat(orders(days)).containsExactly(
                Map.entry("2026-03-28", 1L), Map.entry("2026-03-29", 2L), Map.entry("2026-03-30", 1L),
                Map.entry("2026-03-31", 1L));
        assertThat(days.get("rows").get(1).get("label").asString()).isEqualTo("Sun, Mar 29, 2026");
        assertThat(days.get("columns").get(0).get("label").asString()).isEqualTo("Day");

        JsonNode weeks = preview(table("time", "week", new Window("", "2026-03-25", "2026-04-05"), edgeOnly));
        assertThat(orders(weeks)).containsExactly(Map.entry("2026-03-23", 3L), Map.entry("2026-03-30", 3L));
        assertThat(weeks.get("rows").get(0).get("partial").asBoolean()).isTrue();
        assertThat(weeks.get("rows").get(1).get("partial").asBoolean()).isFalse();
        assertThat(weeks.get("rows").get(0).get("label").asString()).isEqualTo("Week of Mar 23, 2026");

        JsonNode months = preview(table("time", "month", new Window("", "2026-03-15", "2026-04-15"), edgeOnly));
        assertThat(orders(months)).containsExactly(Map.entry("2026-03-01", 5L), Map.entry("2026-04-01", 1L));
        assertThat(months.get("rows").get(0).get("label").asString()).isEqualTo("March 2026");
        assertThat(months.get("rows").get(0).get("partial").asBoolean()).isTrue();
        assertThat(months.get("totals").get("revenue").decimalValue()).isEqualByComparingTo("60.00");

        JsonNode autumn = preview(table("time", "day", new Window("", "2026-10-25", "2026-10-26"), edgeOnly));
        assertThat(orders(autumn)).containsExactly(Map.entry("2026-10-25", 3L), Map.entry("2026-10-26", 1L));
        assertThat(autumn.get("rows").get(0).get("partial").asBoolean()).isFalse();
    }

    private static Map<String, Long> orders(JsonNode result) {
        Map<String, Long> orders = new LinkedHashMap<>();
        result.get("rows").forEach(row -> orders.put(row.get("key").asString(), row.get("values").get("orders").asLong()));
        return orders;
    }

    @Test
    void topGroupsByTheFirstMetricWithTotalsOverEveryGroup() throws Exception {
        Window year = WINDOWS.get(2);
        JsonNode result = preview("""
                {"title": "Top products", "visualization": "bar", "metrics": ["units"], "groupBy": "product",
                 "range": {"type": "fixed", "from": "%s", "to": "%s"}, "limit": 2}
                """.formatted(year.from(), year.to()));
        Map<String, Totals> expected = expected("product", null, year, Filters.NONE);
        List<Map.Entry<String, Totals>> ranked = expected.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().units(), a.getValue().units()))
                .toList();
        assertThat(result.get("truncated").asBoolean()).isTrue();
        assertThat(result.get("totalGroups").asInt()).isEqualTo(expected.size()).isEqualTo(5);
        assertThat(result.get("rows")).hasSize(2);
        assertThat(result.get("rows").get(0).get("values").get("units").asLong()).isEqualTo(ranked.get(0).getValue().units());
        assertThat(result.get("rows").get(1).get("values").get("units").asLong()).isEqualTo(ranked.get(1).getValue().units());
        assertThat(result.get("rows").get(0).get("values").size()).isEqualTo(1);
        // Totals cover every product, not just the two shown.
        assertThat(result.get("totals").get("units").asLong()).isEqualTo(expectedTotal(year, Filters.NONE).units());
        assertThat(result.get("columns")).hasSize(2);
        String label = result.get("rows").get(0).get("label").asString();
        assertThat(label).isIn("Jacket", "Tee", "Scarf", "Boots", "Socks");

        // Orders by product overlap (a receipt with two products counts in each): groups add up to more
        // than the distinct total, which the totals report.
        JsonNode orders = preview(table("product", null, year, Filters.NONE.json()));
        long sum = 0;
        for (JsonNode row : orders.get("rows")) {
            sum += row.get("values").get("orders").asLong();
        }
        assertThat(sum).isGreaterThan(orders.get("totals").get("orders").asLong());
        assertThat(orders.get("totals").get("orders").asLong()).isEqualTo(expectedTotal(year, Filters.NONE).orders());

        // Ties are broken by label ignoring case; stores without sales are listed with zeros.
        JsonNode stores = preview(table("store", null, WINDOWS.getLast(), Filters.NONE.json()));
        List<String> labels = new ArrayList<>();
        stores.get("rows").forEach(row -> labels.add(row.get("label").asString()));
        assertThat(labels).containsExactly("Alpha", "bravo", "Charlie (no sales)", "Edge cases");
    }

    @Test
    void kpisShowTheSameTotalsAsTheTable() throws Exception {
        Window window = WINDOWS.get(3);
        JsonNode kpi = preview("""
                {"title": "KPIs", "visualization": "kpi", "groupBy": "none",
                 "metrics": ["average_order_value", "revenue", "orders", "units"],
                 "range": {"type": "fixed", "from": "%s", "to": "%s"}}
                """.formatted(window.from(), window.to()));
        Totals total = expectedTotal(window, Filters.NONE);
        assertThat(kpi.get("rows")).hasSize(1);
        assertThat(kpi.get("rows").get(0).get("key").asString()).isEqualTo("total");
        assertThat(kpi.get("columns").get(0).get("key").asString()).isEqualTo("average_order_value");
        List<String> order = new ArrayList<>();
        kpi.get("totals").properties().forEach(e -> order.add(e.getKey()));
        assertThat(order).containsExactly("average_order_value", "revenue", "orders", "units");
        SoftAssertions softly = new SoftAssertions();
        checkValues(softly, "kpi", kpi.get("rows").get(0), total);
        softly.assertAll();
        assertThat(kpi.get("granularity").isNull()).isTrue();
        assertThat(kpi.get("totalGroups").asInt()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- equality with the report API

    @Test
    void monthlyChartsEqualTheMonthlyReport() throws Exception {
        for (Window window : WINDOWS) {
            for (Long store : new Long[] {null, alpha}) {
                Filters filters = store == null ? Filters.NONE : new Filters(List.of(store), List.of(), List.of());
                JsonNode chart = preview(table("time", "month", window, filters.json()));
                JsonNode report = getJson("/api/reports/monthly?from=%s&to=%s%s".formatted(window.from(), window.to(),
                        store == null ? "" : "&storeId=" + store));
                String label = window.name() + " store " + store;
                assertThat(chart.get("rows")).as(label).hasSize(report.get("rows").size());
                for (int i = 0; i < chart.get("rows").size(); i++) {
                    JsonNode row = chart.get("rows").get(i);
                    JsonNode month = report.get("rows").get(i);
                    assertThat(row.get("key").asString()).as(label).isEqualTo(month.get("month").asString());
                    assertThat(row.get("values").get("revenue")).as(label).isEqualTo(month.get("revenue"));
                    assertThat(row.get("values").get("orders")).as(label).isEqualTo(month.get("orders"));
                    assertThat(row.get("values").get("units")).as(label).isEqualTo(month.get("unitsSold"));
                    assertThat(row.get("values").get("average_order_value")).as(label).isEqualTo(month.get("averageOrderValue"));
                    assertThat(row.get("partial").asBoolean()).as(label).isEqualTo(!month.get("complete").asBoolean());
                }
                JsonNode totals = report.get("totals");
                assertThat(chart.get("totals").get("revenue")).as(label).isEqualTo(totals.get("revenue"));
                assertThat(chart.get("totals").get("orders")).as(label).isEqualTo(totals.get("orders"));
                assertThat(chart.get("totals").get("units")).as(label).isEqualTo(totals.get("unitsSold"));
                assertThat(chart.get("totals").get("average_order_value")).as(label).isEqualTo(totals.get("averageOrderValue"));
            }
        }
    }

    @Test
    void categoryChartsEqualTheCategoryReport() throws Exception {
        for (Window window : WINDOWS) {
            for (Long store : new Long[] {null, bravo}) {
                Filters filters = store == null ? Filters.NONE : new Filters(List.of(store), List.of(), List.of());
                JsonNode chart = preview(table("category", null, window, filters.json()));
                JsonNode report = getJson("/api/reports/categories?from=%s&to=%s%s".formatted(window.from(), window.to(),
                        store == null ? "" : "&storeId=" + store));
                String label = window.name() + " store " + store;
                Map<String, List<JsonNode>> chartRows = new LinkedHashMap<>();
                chart.get("rows").forEach(row -> chartRows.put(row.get("key").asString(), List.of(
                        row.get("values").get("revenue"), row.get("values").get("units"), row.get("values").get("orders"))));
                Map<String, List<JsonNode>> reportRows = new LinkedHashMap<>();
                report.get("rows").forEach(row -> reportRows.put(row.get("category").asString(), List.of(
                        row.get("revenue"), row.get("unitsSold"), row.get("orders"))));
                assertThat(chartRows).as(label).isEqualTo(reportRows);
                JsonNode totals = report.get("totals");
                assertThat(chart.get("totals").get("revenue")).as(label).isEqualTo(totals.get("revenue"));
                assertThat(chart.get("totals").get("units")).as(label).isEqualTo(totals.get("unitsSold"));
                assertThat(chart.get("totals").get("orders")).as(label).isEqualTo(totals.get("orders"));
            }
        }
    }
}
