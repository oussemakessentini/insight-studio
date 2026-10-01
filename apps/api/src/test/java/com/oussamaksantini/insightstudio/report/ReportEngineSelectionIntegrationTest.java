package com.oussamaksantini.insightstudio.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.testsupport.ApiInstance;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

/**
 * {@code insight.reports.engine} is checked at startup (docs/cube-reports-contract.md §2): the Cube
 * engine without a configured Cube, an unknown engine or a non-positive timeout stop the API instead
 * of failing (or falling back) at the first report.
 */
class ReportEngineSelectionIntegrationTest extends PostgresIntegrationTest {

    /** No Cube from the environment either (CubeConfiguration also reads INSIGHT_CUBE_URL). */
    private static final Map<String, Object> NO_CUBE = Map.of("insight.cube.url", "", "INSIGHT_CUBE_URL", "");

    @Autowired
    JdbcConnectionDetails database;

    @Test
    void theCubeEngineWithoutCubeFailsStartupWithAClearMessage() {
        assertThatThrownBy(() -> start(Map.of("insight.reports.engine", "cube")))
                .rootCause()
                .hasMessage("insight.reports.engine=cube (REPORTS_ENGINE) needs Cube: set INSIGHT_CUBE_URL and "
                        + "CUBEJS_API_SECRET (see docs/analytics.md), or use REPORTS_ENGINE=sql.");
    }

    @Test
    void unknownEnginesAndNonPositiveTimeoutsFailStartup() {
        assertThatThrownBy(() -> start(Map.of("insight.reports.engine", "duckdb")))
                .hasStackTraceContaining("insight.reports.engine");
        assertThatThrownBy(() -> start(Map.of("insight.reports.cube-timeout", "PT0S")))
                .hasStackTraceContaining("insight.reports.cube-timeout (REPORTS_CUBE_TIMEOUT) must be positive.");
    }

    @Test
    void theSqlEngineIsTheDefaultAndNeedsNoCube() {
        try (ApiInstance api = start(Map.of())) {
            assertThat(api.bean(ReportEngine.class)).isInstanceOf(SqlReportEngine.class);
            assertThat(api.bean(ReportEngine.class).name()).isEqualTo("sql");
        }
    }

    private ApiInstance start(Map<String, Object> properties) {
        Map<String, Object> all = new LinkedHashMap<>(NO_CUBE);
        all.putAll(properties);
        return ApiInstance.start(database, all);
    }
}
