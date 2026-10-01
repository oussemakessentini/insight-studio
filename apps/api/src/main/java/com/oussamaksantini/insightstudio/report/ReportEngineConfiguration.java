package com.oussamaksantini.insightstudio.report;

import com.oussamaksantini.insightstudio.analytics.CubeClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Chooses the report engine from {@link ReportProperties} and labels every report response with
 * {@value #ENGINE_HEADER} (docs/cube-reports-contract.md §2): the {@code /api/reports/**} JSON, CSV
 * and PDF endpoints and the saved-report runs and exports, errors included.
 *
 * <p>The {@code cube} engine needs the Cube client of {@code /api/analytics/summary}
 * ({@code INSIGHT_CUBE_URL} and {@code CUBEJS_API_SECRET}); without it startup fails, rather than
 * every report failing or silently falling back to SQL.
 */
@Configuration(proxyBeanMethods = false)
class ReportEngineConfiguration {

    static final String ENGINE_HEADER = "X-Report-Engine";

    private static final Logger log = LoggerFactory.getLogger(ReportEngineConfiguration.class);

    @Bean
    ReportEngine reportEngine(ReportProperties properties, NamedParameterJdbcTemplate jdbc, ObjectProvider<CubeClient> cube) {
        ReportEngine engine = switch (properties.engine()) {
            case SQL -> new SqlReportEngine(jdbc);
            case CUBE -> CubeReportEngine.create(requireCube(cube.getIfAvailable()), jdbc, properties.cubeTimeout());
        };
        log.info("Reports are computed by the {} engine{}", engine.name(),
                properties.engine() == ReportProperties.Engine.CUBE ? " (deadline " + properties.cubeTimeout() + ")" : "");
        return engine;
    }

    static CubeClient requireCube(CubeClient cube) {
        if (cube == null) {
            throw new IllegalStateException("insight.reports.engine=cube (REPORTS_ENGINE) needs Cube: set INSIGHT_CUBE_URL "
                    + "and CUBEJS_API_SECRET (see docs/analytics.md), or use REPORTS_ENGINE=sql.");
        }
        return cube;
    }

    @Bean
    WebMvcConfigurer reportEngineHeader(ReportEngine engine) {
        // Set before the handler runs, so error responses (problem details) carry it too.
        HandlerInterceptor header = new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader(ENGINE_HEADER, engine.name());
                return true;
            }
        };
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(header).addPathPatterns(
                        "/api/reports/**",
                        "/api/saved-reports/*/report",
                        "/api/saved-reports/*/report.csv",
                        "/api/saved-reports/*/report.pdf");
            }
        };
    }
}
