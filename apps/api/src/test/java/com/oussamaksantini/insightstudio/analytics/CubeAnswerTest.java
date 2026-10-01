package com.oussamaksantini.insightstudio.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oussamaksantini.insightstudio.testsupport.CubeFixtures;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Reading {@code /load} answers, with responses captured from a real Cube v1.7.46 in production mode
 * ({@link CubeFixtures}, see docs/cube-reports.md).
 */
class CubeAnswerTest {

    @Test
    void readsRowsAndTheRollupThatServedThem() {
        CubeAnswer answer = CubeAnswer.of(CubeFixtures.body("monthly-rollup"));

        assertThat(answer.continueWait()).isFalse();
        assertThat(answer.preAggregations()).containsExactly("orders.daily_by_store");
        assertThat(answer.data()).hasSize(3);
        assertThat(answer.data().getFirst())
                .containsEntry("orders.sold_at.month", "2026-04-01T00:00:00.000")
                .containsEntry("orders.revenue", "50.1")
                .containsEntry("orders.count", "1")
                .containsEntry("orders.data_version", "7");
    }

    @Test
    void productionModeWithholdsRefreshKeysAndTableNames() {
        // What the freshness check can rely on: only the rows. usedPreAggregations names the rollup
        // and the build time (seconds), refreshKeyValues and targetTableName are not sent.
        Map<?, ?> body = CubeFixtures.body("monthly-rollup");
        assertThat(body.containsKey("refreshKeyValues")).isFalse();
        Map<?, ?> used = (Map<?, ?>) ((Map<?, ?>) body.get("usedPreAggregations")).values().iterator().next();
        assertThat(used.keySet()).map(Object::toString).containsExactlyInAnyOrder("preAggregationId", "lastUpdatedAt", "type");
    }

    @Test
    void anEmptyPeriodHasNoRowsWhileTheVerifiedFormHasTheMarkerRow() {
        assertThat(CubeAnswer.of(CubeFixtures.body("monthly-rollup-empty")).data()).isEmpty();
        assertThat(CubeAnswer.of(CubeFixtures.body("order-totals-rollup-empty")).data().getFirst())
                .containsEntry("orders.count", null)
                .containsEntry("orders.data_version", null);

        CubeAnswer verified = CubeAnswer.of(CubeFixtures.body("monthly-verified-empty"));
        assertThat(verified.preAggregations()).as("runs on PostgreSQL").isEmpty();
        assertThat(verified.data()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("orders.sold_at.month", null)
                .containsEntry("orders.count", "0")
                .containsEntry("orders.data_version", "7"));
    }

    @Test
    void continueWaitIsNotAnError() {
        CubeAnswer answer = CubeAnswer.of(Map.of("error", "Continue wait"));

        assertThat(answer.continueWait()).isTrue();
        assertThat(answer.data()).isEmpty();
    }

    @Test
    void errorsAndMalformedBodiesAreFailures() {
        assertThatThrownBy(() -> CubeAnswer.of(CubeFixtures.body("error-unknown-cube"))).isInstanceOf(CubeException.class);
        assertThatThrownBy(() -> CubeAnswer.of(CubeFixtures.body("error-invalid-token"))).isInstanceOf(CubeException.class);
        assertThatThrownBy(() -> CubeAnswer.of(Map.of("something", "else"))).isInstanceOf(CubeException.class);
        assertThatThrownBy(() -> CubeAnswer.of(Map.of("data", "not rows"))).isInstanceOf(CubeException.class);
        assertThatThrownBy(() -> CubeAnswer.of(null)).isInstanceOf(CubeException.class);
    }
}
