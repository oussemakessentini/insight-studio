package com.oussamaksantini.insightstudio.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Operations endpoints (docs/operations.md): health probes, the {@code jobs} health of the background
 * queues, Prometheus metrics, and request ids. All are reachable without a session (in production they live
 * on the unpublished management port). Metrics export is on here ({@link AutoConfigureMetrics}): tests turn
 * it off by default.
 */
@AutoConfigureMetrics
class OpsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobQueues queues;

    @Autowired
    JobHeartbeats heartbeats;

    @BeforeEach
    void setUp() {
        new SqlFixture(jdbc).clear();
        queues.refresh();
    }

    @Test
    void livenessAndReadinessProbesAnswerWithoutASession() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void delayedOrFailingQueuesTurnJobsHealthDownWithoutTouchingReadiness() throws Exception {
        mvc.perform(get("/actuator/health/jobs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.backgroundJobs.details.billing_events.status").value("OK"));

        // A webhook event retried 7 times, and a mail overdue by 20 minutes.
        jdbc.update("""
                INSERT INTO billing_events (provider, event_id, event_type, attempts, next_attempt_at)
                VALUES ('fake', 'evt_stuck', 'invoice.paid', 7, now() + interval '1 hour')
                """);
        jdbc.update("""
                INSERT INTO mail_outbox (kind, recipient, subject, body, next_attempt_at)
                VALUES ('test', 'x@example.com', 'Hi', 'Body', now() - interval '20 minutes')
                """);
        queues.refresh();

        mvc.perform(get("/actuator/health/jobs")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.backgroundJobs.details.billing_events.status").value("FAILING"))
                .andExpect(jsonPath("$.components.backgroundJobs.details.billing_events.maxAttempts").value(7))
                .andExpect(jsonPath("$.components.backgroundJobs.details.mail_outbox.status").value("DELAYED"))
                .andExpect(jsonPath("$.components.backgroundJobs.details.cube_purge.status").value("OK"));
        // Instances stay in service: the queues are shared, a restart would not help.
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());

        String metrics = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(metrics).containsPattern("insight_jobs_pending\\{application=\"insight-api\",queue=\"billing_events\"} 1\\.0")
                .containsPattern("insight_jobs_max_attempts\\{application=\"insight-api\",queue=\"billing_events\"} 7\\.0")
                .containsPattern("insight_jobs_overdue_seconds\\{application=\"insight-api\",queue=\"mail_outbox\"} 1[12]\\d\\d");
    }

    @Test
    void prometheusExposesHttpAndJobMetrics() throws Exception {
        mvc.perform(get("/api/session")).andExpect(status().isOk());
        heartbeats.succeeded("ops_test");
        String metrics = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(metrics).contains("http_server_requests_seconds_bucket")
                .contains("insight_job_last_success_seconds{application=\"insight-api\",job=\"ops_test\"}")
                .contains("hikaricp_connections_active")
                .contains("jvm_memory_used_bytes");
    }

    @Test
    void everyResponseCarriesARequestIdAndKeepsAWellFormedOne() throws Exception {
        String generated = mvc.perform(get("/api/session")).andExpect(status().isOk())
                .andReturn().getResponse().getHeader(RequestIdFilter.HEADER);
        assertThat(generated).matches("[0-9a-f-]{36}");
        mvc.perform(get("/api/session").header(RequestIdFilter.HEADER, "proxy-1234abcd"))
                .andExpect(header().string(RequestIdFilter.HEADER, "proxy-1234abcd"));
        String replaced = mvc.perform(get("/api/session").header(RequestIdFilter.HEADER, "bad id\nwith newline"))
                .andReturn().getResponse().getHeader(RequestIdFilter.HEADER);
        assertThat(replaced).matches("[0-9a-f-]{36}");
    }
}
