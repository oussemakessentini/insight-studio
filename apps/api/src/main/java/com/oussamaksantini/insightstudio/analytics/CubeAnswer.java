package com.oussamaksantini.insightstudio.analytics;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One answer of Cube's {@code POST /cubejs-api/v1/load}: either the result rows or "still working"
 * ({@code {"error":"Continue wait"}}, which Cube sends while a query or a pre-aggregation build
 * takes longer than its wait timeout; the caller asks again).
 *
 * <p>Of the rest of the body only {@code usedPreAggregations} is kept, as the ids of the rollups that
 * served the query (for logs and tests). In production mode Cube 1.7.46 publishes just
 * {@code preAggregationId}, {@code lastUpdatedAt} and {@code type} per rollup; the refresh-key values
 * and table names are withheld (docs/cube-reports.md).
 *
 * @param continueWait Cube is still working; {@code data} is empty
 * @param data the result rows, keyed by member name ({@code "orders.revenue"}); Cube sends numbers as
 *     strings and missing aggregates as {@code null}
 * @param preAggregations {@code preAggregationId}s of the rollups that served the query, empty when
 *     it ran against the database
 */
public record CubeAnswer(boolean continueWait, List<Map<String, Object>> data, List<String> preAggregations) {

    static final String CONTINUE_WAIT = "Continue wait";

    private static final Logger log = LoggerFactory.getLogger(CubeAnswer.class);

    /**
     * Reads a parsed {@code /load} response body.
     *
     * @throws CubeException when the body carries another error or has no data rows
     */
    @SuppressWarnings("unchecked")
    public static CubeAnswer of(Map<?, ?> body) {
        if (body == null) {
            log.warn("Cube returned an empty response");
            throw new CubeException("Empty Cube response", false);
        }
        Object error = body.get("error");
        if (CONTINUE_WAIT.equals(error)) {
            return new CubeAnswer(true, List.of(), List.of());
        }
        if (error != null) {
            log.warn("Cube returned an error: {}", CubeClient.abbreviate(error));
            throw new CubeException("Cube error", false);
        }
        Object data = body.get("data");
        if (!(data instanceof List<?> list) || !list.stream().allMatch(Map.class::isInstance)) {
            log.warn("Cube returned a response without data rows");
            throw new CubeException("Cube response without data", false);
        }
        List<String> preAggregations = List.of();
        if (body.get("usedPreAggregations") instanceof Map<?, ?> used) {
            preAggregations = used.values().stream()
                    .map(usage -> usage instanceof Map<?, ?> m ? m.get("preAggregationId") : null)
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .sorted()
                    .toList();
        }
        return new CubeAnswer(false, (List<Map<String, Object>>) list, preAggregations);
    }
}
