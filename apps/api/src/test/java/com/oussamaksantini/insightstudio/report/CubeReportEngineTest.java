package com.oussamaksantini.insightstudio.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oussamaksantini.insightstudio.analytics.CubeAnswer;
import com.oussamaksantini.insightstudio.analytics.CubeClient.CacheMode;
import com.oussamaksantini.insightstudio.analytics.CubeException;
import com.oussamaksantini.insightstudio.common.web.ServiceUnavailableException;
import com.oussamaksantini.insightstudio.report.ReportEngine.CategoryBreakdown;
import com.oussamaksantini.insightstudio.report.ReportEngine.CategoryTotals;
import com.oussamaksantini.insightstudio.report.ReportEngine.MonthTotals;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import com.oussamaksantini.insightstudio.testsupport.CubeFixtures;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The Cube engine's freshness check, retries, deadline and failure mapping, against answers captured
 * from a real Cube ({@link CubeFixtures}: data version 7, and 9 after a change).
 */
class CubeReportEngineTest {

    private static final ReportFilter FILTER = new ReportFilter(
            1, ZoneId.of("Europe/Paris"), LocalDate.parse("2026-03-20"), LocalDate.parse("2026-06-10"), null);
    private static final ReportFilter STORE_FILTER = new ReportFilter(
            1, ZoneId.of("Europe/Paris"), LocalDate.parse("2026-03-20"), LocalDate.parse("2026-06-10"), 2L);

    /** One call the engine made. */
    record Call(Map<String, Object> query, CacheMode cache, Duration timeout) {

        boolean mustRevalidate() {
            return cache == CacheMode.MUST_REVALIDATE;
        }
    }

    private final List<Call> calls = new ArrayList<>();
    private final AtomicLong dataVersion = new AtomicLong(7);

    /** An engine whose Cube answers each call with {@code answers} (call -> answer). */
    private CubeReportEngine engine(Duration timeout, Function<Call, CubeAnswer> answers) {
        return new CubeReportEngine(
                (businessId, query, cache, callTimeout) -> {
                    assertThat(businessId).isEqualTo(1);
                    Call call = new Call(query, cache, callTimeout);
                    calls.add(call);
                    return answers.apply(call);
                },
                dataVersion::get,
                rows -> rows.stream()
                        .sorted(Comparator.comparing(CategoryTotals::revenue).reversed().thenComparing(CategoryTotals::category))
                        .toList(),
                timeout);
    }

    private static CubeAnswer answer(String fixture) {
        return CubeAnswer.of(CubeFixtures.body(fixture));
    }

    private static boolean isVerifiedForm(Call call) {
        return call.query().toString().contains("notSet");
    }

    // ---------------------------------------------------------------- fresh answers

    @Test
    void monthlyRowsComeFromTheRollupWhenTheirVersionIsCurrent() {
        List<MonthTotals> months = engine(Duration.ofSeconds(5), call -> answer("monthly-rollup")).monthly(FILTER);

        assertThat(months).containsExactly(
                new MonthTotals(LocalDate.parse("2026-04-01"), new BigDecimal("50.10"), 1, 1),
                new MonthTotals(LocalDate.parse("2026-05-01"), new BigDecimal("70.40"), 2, 5),
                new MonthTotals(LocalDate.parse("2026-06-01"), new BigDecimal("60.30"), 1, 2));
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.cache()).isEqualTo(CacheMode.DEFAULT);
            assertThat(call.query()).isEqualTo(CubeReportQueries.monthly(FILTER));
        });
    }

    @Test
    void aNewerVersionThanRequiredIsAccepted() {
        dataVersion.set(5);

        assertThat(engine(Duration.ofSeconds(5), call -> answer("monthly-rollup")).monthly(FILTER)).hasSize(3);
    }

    @Test
    void anEmptyPeriodIsVerifiedWithTheMarkerRow() {
        List<MonthTotals> months = engine(Duration.ofSeconds(5), call -> isVerifiedForm(call)
                ? answer("monthly-verified-empty")
                : answer("monthly-rollup-empty")).monthly(FILTER);

        assertThat(months).isEmpty();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1).query()).isEqualTo(CubeReportQueries.monthlyVerified(FILTER));
        // Not a rollup query: Cube must run it, not answer from its result cache.
        assertThat(calls.get(1).cache()).isEqualTo(CacheMode.NO_CACHE);
    }

    @Test
    void anEmptyRollupAnswerIsNeverTakenAsProofOfNoSales() {
        // The rollup says "no rows" and the marker query is stale: the empty answer is not used.
        dataVersion.set(9);
        assertThatThrownBy(() -> engine(Duration.ofMillis(300), call -> isVerifiedForm(call)
                ? answer("monthly-verified-empty")
                : answer("monthly-rollup-empty")).monthly(FILTER))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo(CubeReportEngine.UPDATING);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(5);
                });
    }

    @Test
    void categoriesListTheCatalogueWithZerosAndTotalDistinctOrders() {
        CategoryBreakdown breakdown = engine(Duration.ofSeconds(5), call -> {
            String query = call.query().toString();
            if (query.contains("orders.count")) {
                return answer("order-totals-rollup");
            }
            return query.contains("dateRange") ? answer("categories-rollup") : answer("catalogue");
        }).categories(FILTER);

        assertThat(breakdown.categories()).containsExactly(
                new CategoryTotals("Outerwear", new BigDecimal("120.40"), 5, 3),
                new CategoryTotals("Tops", new BigDecimal("60.40"), 3, 2),
                new CategoryTotals("Zzz", new BigDecimal("0.00"), 0, 0));
        // Orders containing each category add up to 5, the distinct orders are 4.
        assertThat(breakdown.total()).isEqualTo(new CategoryTotals(null, new BigDecimal("180.80"), 8, 4));
        assertThat(calls).extracting(Call::query).containsExactly(
                CubeReportQueries.categories(FILTER), CubeReportQueries.catalogue(FILTER),
                CubeReportQueries.orderTotals(FILTER));
    }

    @Test
    void categoriesOfAnEmptyPeriodComeFromTheVerifiedForms() {
        CategoryBreakdown breakdown = engine(Duration.ofSeconds(5), call -> {
            String query = call.query().toString();
            if (query.contains("orders.count")) {
                return isVerifiedForm(call) ? CubeAnswer.of(Map.of("data", List.of(
                        Map.of("orders.count", "0", "orders.data_version", "7")))) : answer("order-totals-rollup-empty");
            }
            if (isVerifiedForm(call)) {
                return answer("categories-verified-empty");
            }
            return query.contains("dateRange") ? answer("monthly-rollup-empty") : answer("catalogue");
        }).categories(FILTER);

        assertThat(breakdown.categories()).extracting(CategoryTotals::category).containsExactly("Outerwear", "Tops", "Zzz");
        assertThat(breakdown.categories()).allSatisfy(c -> assertThat(c.orders()).isZero());
        assertThat(breakdown.total()).isEqualTo(new CategoryTotals(null, new BigDecimal("0.00"), 0, 0));
    }

    // ---------------------------------------------------------------- stale answers

    @Test
    void aStaleAnswerIsAskedAgainWithMustRevalidate() {
        dataVersion.set(9);

        List<MonthTotals> months = engine(Duration.ofSeconds(5), call -> call.mustRevalidate()
                ? answer("monthly-rollup-revalidated")
                : answer("monthly-rollup-stale")).monthly(FILTER);

        assertThat(months).last().isEqualTo(new MonthTotals(LocalDate.parse("2026-06-01"), new BigDecimal("140.26"), 2, 6));
        assertThat(calls).extracting(Call::mustRevalidate).containsExactly(false, true);
    }

    @Test
    void keepsRevalidatingWhileCubeStillServesTheOldBuild() {
        // Cube caches refresh-key values for about a second; a revalidation within it still serves the old build.
        dataVersion.set(9);
        int[] stale = {3};

        List<MonthTotals> months = engine(Duration.ofSeconds(5), call -> stale[0]-- > 0
                ? answer("monthly-rollup-stale")
                : answer("monthly-rollup-revalidated")).monthly(FILTER);

        assertThat(months).hasSize(3);
        assertThat(calls).extracting(Call::mustRevalidate).containsExactly(false, true, true, true);
    }

    @Test
    void stillStaleAtTheDeadlineIsA503ToRetryInFiveSeconds() {
        dataVersion.set(9);

        assertThatThrownBy(() -> engine(Duration.ofMillis(400), call -> answer("monthly-rollup-stale")).monthly(FILTER))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo(
                            "Report figures are being updated after recent changes. Try again in a few seconds.");
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(5);
                });
        assertThat(calls).hasSizeGreaterThan(1);
        assertThat(calls.subList(1, calls.size())).allMatch(Call::mustRevalidate);
    }

    @Test
    void figuresOfOneReportMustShareOneVersion() {
        // Categories at version 9, catalogue and order totals still at 7: asked again until they agree.
        dataVersion.set(7);
        CategoryBreakdown breakdown = engine(Duration.ofSeconds(5), call -> {
            String query = call.query().toString();
            if (query.contains("orders.count")) {
                return call.mustRevalidate() ? withVersion("order-totals-rollup", "9") : answer("order-totals-rollup");
            }
            if (query.contains("dateRange")) {
                return withVersion("categories-rollup", "9");
            }
            return call.mustRevalidate() ? withVersion("catalogue", "9") : answer("catalogue");
        }).categories(FILTER);

        assertThat(breakdown.total().orders()).isEqualTo(4);
        assertThat(calls).extracting(Call::mustRevalidate).containsExactly(false, false, false, true, true, true);
    }

    @Test
    void aBusinessMissingFromTheBuildIsStale() {
        // A business created after the last build has no marker row yet.
        assertThatThrownBy(() -> engine(Duration.ofMillis(300), call -> CubeAnswer.of(Map.of("data", List.of())))
                .categories(FILTER))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(5));
    }

    // ---------------------------------------------------------------- Cube failures and timeouts

    @Test
    void continueWaitIsPolledWithinTheDeadline() {
        int[] waits = {2};

        List<MonthTotals> months = engine(Duration.ofSeconds(5), call -> waits[0]-- > 0
                ? CubeAnswer.of(Map.of("error", "Continue wait"))
                : answer("monthly-rollup")).monthly(FILTER);

        assertThat(months).hasSize(3);
        assertThat(calls).hasSize(3);
        // Each call may only wait for what is left of the request's deadline.
        assertThat(calls).allSatisfy(call -> assertThat(call.timeout()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(5)));
        assertThat(calls.get(2).timeout()).isLessThan(calls.get(0).timeout());
    }

    @Test
    void stillBuildingAtTheDeadlineIsA503ToRetryInThirtySeconds() {
        assertThatThrownBy(() -> engine(Duration.ofMillis(400), call -> CubeAnswer.of(Map.of("error", "Continue wait")))
                .monthly(FILTER))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("Report figures are temporarily unavailable. Try again in a minute.");
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(30);
                });
    }

    @Test
    void stillBuildingWhileCatchingUpWithAChangeIsReportedAsUpdating() {
        dataVersion.set(9);

        assertThatThrownBy(() -> engine(Duration.ofMillis(400), call -> call.mustRevalidate()
                ? CubeAnswer.of(Map.of("error", "Continue wait"))
                : answer("monthly-rollup-stale")).monthly(FILTER))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(5));
    }

    @Test
    void aCallCutShortByTheDeadlineIsStillBuilding() {
        assertThatThrownBy(() -> engine(Duration.ofMillis(200), call -> {
            sleep(call.timeout());
            throw cubeException(true);
        }).monthly(FILTER))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(30));
    }

    @Test
    void aCallThatTimesOutBeforeTheDeadlineIsAskedAgain() {
        int[] timeouts = {1};

        List<MonthTotals> months = engine(Duration.ofSeconds(5), call -> {
            if (timeouts[0]-- > 0) {
                throw cubeException(true);
            }
            return answer("monthly-rollup");
        }).monthly(FILTER);

        assertThat(months).hasSize(3);
        assertThat(calls).hasSize(2);
    }

    @Test
    void cubeFailuresAreA503ToRetryInAMinute() {
        assertThatThrownBy(() -> engine(Duration.ofSeconds(5), call -> {
            throw cubeException(false);
        }).categories(FILTER))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo(CubeReportEngine.UNAVAILABLE);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(60);
                });
        assertThat(calls).hasSize(1);
    }

    @Test
    void invalidAnswersAreA503ToRetryInAMinute() {
        List<Map<String, Object>> twoVersions = List.of(
                Map.of("orders.sold_at.month", "2026-04-01T00:00:00.000", "orders.count", "1", "orders.data_version", "7"),
                Map.of("orders.sold_at.month", "2026-05-01T00:00:00.000", "orders.count", "1", "orders.data_version", "8"));
        List<Map<String, Object>> notANumber = List.of(
                Map.of("orders.sold_at.month", "2026-04-01T00:00:00.000", "orders.revenue", "lots", "orders.data_version", "7"));
        for (List<Map<String, Object>> rows : List.of(twoVersions, notANumber)) {
            assertThatThrownBy(() -> engine(Duration.ofSeconds(5), call -> CubeAnswer.of(Map.of("data", rows))).monthly(FILTER))
                    .isInstanceOfSatisfying(ServiceUnavailableException.class,
                            e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(60));
        }
    }

    // ---------------------------------------------------------------- queries

    @Test
    void rollupQueriesFilterTheStoreAndVerifiedQueriesKeepTheMarkerRowsOutsideTheStoreFilter() {
        Map<String, Object> rollup = CubeReportQueries.categories(STORE_FILTER);
        assertThat(rollup).containsEntry("timezone", "Europe/Paris").containsEntry("limit", 10_000);
        assertThat(rollup.get("timeDimensions").toString()).contains("dateRange=[2026-03-20, 2026-06-10]");
        assertThat(rollup.get("filters").toString())
                .contains("member=order_categories.business_id", "values=[1]", "member=order_categories.store_id", "values=[2]");

        Map<String, Object> verified = CubeReportQueries.monthlyVerified(STORE_FILTER);
        assertThat(verified.get("timeDimensions").toString()).doesNotContain("dateRange");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filters = (List<Map<String, Object>>) verified.get("filters");
        assertThat(filters).hasSize(2);
        assertThat(filters.get(1)).isEqualTo(Map.of("or", List.of(
                Map.of("and", List.of(
                        Map.of("member", "orders.sold_at", "operator", "inDateRange", "values", List.of("2026-03-20", "2026-06-10")),
                        Map.of("member", "orders.store_id", "operator", "equals", "values", List.of("2")))),
                Map.of("member", "orders.sold_at", "operator", "notSet"))));
    }

    // ---------------------------------------------------------------- helpers

    /** A captured answer with every row's data version replaced. */
    @SuppressWarnings("unchecked")
    private static CubeAnswer withVersion(String fixture, String version) {
        Map<String, Object> body = (Map<String, Object>) CubeFixtures.body(fixture);
        for (Map<String, Object> row : (List<Map<String, Object>>) body.get("data")) {
            row.replaceAll((member, value) -> member.endsWith(".data_version") ? version : value);
        }
        return CubeAnswer.of(body);
    }

    private static CubeException cubeException(boolean timedOut) {
        return new CubeException("test", timedOut);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
