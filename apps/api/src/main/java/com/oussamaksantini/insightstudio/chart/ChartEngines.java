package com.oussamaksantini.insightstudio.chart;

import com.oussamaksantini.insightstudio.analytics.CubeClient;
import com.oussamaksantini.insightstudio.chart.ChartDefinition.Engine;
import com.oussamaksantini.insightstudio.report.CubeFreshness;
import com.oussamaksantini.insightstudio.report.ReportProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The chart engines this instance offers: {@code sql} always, {@code cube} only when Cube is
 * configured ({@code INSIGHT_CUBE_URL} and its secret, as for {@code /api/analytics/summary}). Unlike
 * the reports, a chart chooses its engine in its definition; {@code insight.reports.engine} does not
 * matter here.
 */
@Component
class ChartEngines {

    private static final Logger log = LoggerFactory.getLogger(ChartEngines.class);

    private final ChartEngine sql;
    private final ChartEngine cube;

    ChartEngines(
            NamedParameterJdbcTemplate jdbc,
            ChartProperties charts,
            ReportProperties reports,
            ObjectProvider<CubeClient> cubeClient) {
        this.sql = new SqlChartEngine(jdbc, charts.statementTimeout());
        CubeClient client = cubeClient.getIfAvailable();
        this.cube = client == null
                ? null
                : new CubeChartEngine(CubeFreshness.create(client, jdbc, reports.cubeTimeout()));
        log.info("Chart engines: {}", available().stream().map(Engine::key).toList());
    }

    /** The engines definitions may use, {@code sql} first. */
    List<Engine> available() {
        return cube == null ? List.of(Engine.SQL) : List.of(Engine.SQL, Engine.CUBE);
    }

    boolean offers(Engine engine) {
        return available().contains(engine);
    }

    /** The engine of a validated definition (which only names an available one). */
    ChartEngine engine(Engine engine) {
        return switch (engine) {
            case SQL -> sql;
            case CUBE -> {
                if (cube == null) {
                    throw new IllegalStateException("Cube is not configured");
                }
                yield cube;
            }
        };
    }
}
