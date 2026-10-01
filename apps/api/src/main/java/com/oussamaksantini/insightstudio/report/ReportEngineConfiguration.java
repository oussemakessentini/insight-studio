package com.oussamaksantini.insightstudio.report;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 */
@Configuration(proxyBeanMethods = false)
class ReportEngineConfiguration {

    static final String ENGINE_HEADER = "X-Report-Engine";

    @Bean
    ReportEngine reportEngine(ReportProperties properties, NamedParameterJdbcTemplate jdbc) {
        return switch (properties.engine()) {
            case SQL -> new SqlReportEngine(jdbc);
            case CUBE -> throw new IllegalStateException("The cube report engine is not available yet.");
        };
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
