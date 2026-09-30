package com.oussamaksantini.insightstudio.analytics;

import com.oussamaksantini.insightstudio.common.web.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls Cube's REST API ({@code POST /cubejs-api/v1/load}) with a fresh token for one business.
 * Cube answers long-running queries with {@code {"error":"Continue wait"}}; the client polls until
 * the result is ready or {@link #MAX_WAIT} has passed. Every failure becomes a 502 problem detail
 * whose message does not reveal Cube's internals (they are logged instead).
 */
public class CubeClient {

    static final String UNAVAILABLE = "Analytics is temporarily unavailable.";
    static final Duration MAX_WAIT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String CONTINUE_WAIT = "Continue wait";

    private static final Logger log = LoggerFactory.getLogger(CubeClient.class);

    private final RestClient http;
    private final CubeTokens tokens;

    CubeClient(RestClient http, CubeTokens tokens) {
        this.http = http;
        this.tokens = tokens;
    }

    /**
     * Runs {@code query} as {@code businessId} and returns Cube's {@code data} rows. The token's
     * {@code businessId} makes Cube add its own mandatory business filter to the query.
     */
    List<Map<String, Object>> load(long businessId, Map<String, Object> query) {
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        while (true) {
            Map<?, ?> body = post(businessId, query);
            Object error = body.get("error");
            if (CONTINUE_WAIT.equals(error)) {
                if (System.nanoTime() > deadline) {
                    log.warn("Cube did not finish the query within {}", MAX_WAIT);
                    throw unavailable();
                }
                sleep();
                continue;
            }
            if (error != null) {
                log.warn("Cube returned an error: {}", abbreviate(error));
                throw unavailable();
            }
            return rows(body.get("data"));
        }
    }

    private Map<?, ?> post(long businessId, Map<String, Object> query) {
        try {
            Map<?, ?> body = http.post()
                    .uri("/cubejs-api/v1/load")
                    .header("Authorization", "Bearer " + tokens.sign(businessId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", query))
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                log.warn("Cube returned an empty response");
                throw unavailable();
            }
            return body;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                log.error("Cube rejected the API's token (HTTP {}); check that the API and Cube use the same "
                        + "CUBEJS_API_SECRET", e.getStatusCode().value());
            } else {
                log.warn("Cube answered HTTP {}: {}", e.getStatusCode().value(),
                        abbreviate(e.getResponseBodyAsString()));
            }
            throw unavailable();
        } catch (RestClientException e) {
            log.warn("Cube request failed: {}", e.getMessage());
            throw unavailable();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object data) {
        if (!(data instanceof List<?> list) || !list.stream().allMatch(Map.class::isInstance)) {
            log.warn("Cube returned a response without data rows");
            throw unavailable();
        }
        return (List<Map<String, Object>>) list;
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, UNAVAILABLE);
    }
}
