package com.oussamaksantini.insightstudio.chart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oussamaksantini.insightstudio.chart.ChartDefinition.Filters;
import com.oussamaksantini.insightstudio.chart.ChartEngine.ChartQuery;
import com.oussamaksantini.insightstudio.reporting.Granularity;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The SQL engine only ever runs its fixed statements (docs/chart-builder-contract.md §3). */
class SqlChartEngineTest {

    private static final ReportFilter PERIOD = new ReportFilter(
            1, ZoneId.of("Europe/Paris"), LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"), null);

    @Test
    void statementsAreFixedTemplatesWithBoundValues() {
        String hostile = "Tops'); DROP TABLE sales; --";
        Set<String> statements = new HashSet<>();
        for (ChartGroupBy groupBy : ChartGroupBy.values()) {
            for (Filters filters : List.of(Filters.NONE, new Filters(List.of(3L), List.of(hostile), List.of(7L)))) {
                String sql = SqlChartEngine.sql(new ChartQuery(PERIOD, groupBy,
                        groupBy == ChartGroupBy.TIME ? Granularity.WEEK : null, filters));
                assertThat(sql).doesNotContain(hostile, "DROP", "week", "Europe/Paris", "2026");
                assertThat(sql).contains(":businessId", ":start", ":end");
                if (filters != Filters.NONE) {
                    assertThat(sql).contains("IN (:storeIds)", "IN (:productIds)", "IN (:categories)");
                }
                statements.add(sql);
            }
        }
        // 5 groupings x (no filter, every filter): nothing else can be produced.
        assertThat(statements).hasSize(10);
        assertThat(SqlChartEngine.TEMPLATES).hasSize(ChartGroupBy.values().length);
        assertThat(SqlChartEngine.sql(new ChartQuery(PERIOD, ChartGroupBy.TIME, Granularity.DAY, Filters.NONE)))
                .contains("date_trunc(:unit, s.sold_at AT TIME ZONE :tz)", "GROUPING SETS ((group_key), ())");
    }

    @Test
    void queriesOnlyRunInsideATransaction() {
        SqlChartEngine engine = new SqlChartEngine(null, Duration.ofSeconds(10));
        assertThatThrownBy(() -> engine.figures(new ChartQuery(PERIOD, ChartGroupBy.NONE, null, Filters.NONE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
    }
}
