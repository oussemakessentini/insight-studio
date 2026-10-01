package com.oussamaksantini.insightstudio.analytics;

import com.oussamaksantini.insightstudio.common.web.ApiException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls Cube's REST API ({@code POST /cubejs-api/v1/load}) with a fresh token for one business.
 *
 * <ul>
 *   <li>{@link #send} makes exactly one call and returns Cube's {@link CubeAnswer} (rows or
 *       "Continue wait"); callers that own a deadline (the Cube report engine) poll with it.</li>
 *   <li>{@link #load} serves {@code /api/analytics/summary}: it polls while Cube answers
 *       {@code {"error":"Continue wait"}}, up to {@link #MAX_WAIT}, and turns every failure into a 502
 *       problem detail.</li>
 * </ul>
 * Neither reveals Cube's internals to API clients; they are logged instead.
 */
public class CubeClient {

    static final String UNAVAILABLE = "Analytics is temporarily unavailable.";
    static final Duration MAX_WAIT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private static final Logger log = LoggerFactory.getLogger(CubeClient.class);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final Duration readTimeout;
    private final CubeTokens tokens;
    private final RestClient http;

    /**
     * @param readTimeout the longest any single call may wait for Cube's answer; {@link #send} can
     *     shorten it for one call
     */
    CubeClient(HttpClient httpClient, URI base, Duration readTimeout, CubeTokens tokens) {
        this.httpClient = httpClient;
        this.baseUrl = base.toString();
        this.readTimeout = readTimeout;
        this.tokens = tokens;
        this.http = restClient(readTimeout);
    }

    /**
     * Runs {@code query} as {@code businessId} and returns Cube's {@code data} rows. The token's
     * {@code businessId} makes Cube add its own mandatory business filter to the query.
     */
    List<Map<String, Object>> load(long businessId, Map<String, Object> query) {
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        while (true) {
            CubeAnswer answer;
            try {
                answer = post(http, businessId, Map.of("query", query));
            } catch (CubeException e) {
                throw unavailable();
            }
            if (answer.continueWait()) {
                if (System.nanoTime() > deadline) {
                    log.warn("Cube did not finish the query within {}", MAX_WAIT);
                    throw unavailable();
                }
                sleep();
                continue;
            }
            return answer.data();
        }
    }

    /**
     * One {@code /load} call for {@code businessId} (whose id goes into the token, so Cube adds its
     * mandatory business filter).
     *
     * @param mustRevalidate send {@code "cache": "must-revalidate"}: Cube re-reads the refresh keys and
     *     waits for (or starts) a rebuild of out-of-date rollups and cached results instead of serving
     *     them (Cube 1.7's replacement for the former {@code renewQuery: true})
     * @param timeout the longest this call may wait for an answer (capped at the client's read timeout)
     * @throws CubeException when Cube is unreachable, times out, answers an HTTP error or an error
     *     other than "Continue wait", or sends no data rows
     */
    public CubeAnswer send(long businessId, Map<String, Object> query, boolean mustRevalidate, Duration timeout) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        if (mustRevalidate) {
            body.put("cache", "must-revalidate");
        }
        Duration callTimeout = timeout.compareTo(readTimeout) < 0 ? timeout : readTimeout;
        return post(callTimeout.equals(readTimeout) ? http : restClient(callTimeout), businessId, body);
    }

    private CubeAnswer post(RestClient client, long businessId, Map<String, Object> body) {
        try {
            Map<?, ?> response = client.post()
                    .uri("/cubejs-api/v1/load")
                    .header("Authorization", "Bearer " + tokens.sign(businessId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return CubeAnswer.of(response);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                log.error("Cube rejected the API's token (HTTP {}); check that the API and Cube use the same "
                        + "CUBEJS_API_SECRET", e.getStatusCode().value());
            } else {
                log.warn("Cube answered HTTP {}: {}", e.getStatusCode().value(),
                        abbreviate(e.getResponseBodyAsString()));
            }
            throw new CubeException("Cube answered HTTP " + e.getStatusCode().value(), false);
        } catch (RestClientException e) {
            boolean timedOut = isTimeout(e);
            log.warn("Cube request {}: {}", timedOut ? "timed out" : "failed", e.getMessage());
            throw new CubeException("Cube request failed", timedOut);
        }
    }

    private RestClient restClient(Duration timeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, UNAVAILABLE);
    }
}
