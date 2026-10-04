package com.oussamaksantini.insightstudio;

import com.oussamaksantini.insightstudio.testsupport.TestSupportConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Base for tests that need the full application and a real database. Sharing one configuration
 * means Spring caches a single context, so only one PostgreSQL container starts per test run.
 * A real server also runs on a random port, for tests that must go through HTTP (cookies, the
 * servlet container's upload limits). Tests must set up (and may truncate) the data they rely on.
 */
// The shared context captures emails instead of queueing them; no outbox worker runs in it (the
// mail tests start their own instances with one).
// Neither the Cube purge worker, the billing workers nor the retention purge runs on its own: tests call them.
// The Free plan's limits are raised to the absolute caps here so that tests written before billing can
// create what they need; the billing tests (BillingIntegrationTest) run with the shipped defaults.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "insight.mail.outbox.enabled=false", "insight.cube-purge.poll-interval=PT24H", "insight.retention.cron=-",
    "insight.billing.worker.poll-interval=PT24H",
    "insight.billing.plans.free.limits.members=100000", "insight.billing.plans.free.limits.stores=100000",
    "insight.billing.plans.free.limits.charts=200", "insight.billing.plans.free.limits.dashboards=50",
    "insight.billing.plans.free.limits.imports-per-month=100000"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestSupportConfiguration.class})
public abstract class PostgresIntegrationTest {

    protected static final String TRUNCATE_ALL = "TRUNCATE chart_run_slots, billing_events, billing_operations, billing_subscription_leases, billing_cancellations, fake_billing_objects, "
            + "business_subscriptions, audit_events, cube_purge_requests, mail_outbox, email_verification_tokens, spring_session, rate_limit_hits, invitations, password_reset_tokens, dashboard_chart_refs, dashboard_revisions, dashboards, chart_definition_revisions, chart_definitions, saved_reports, memberships, users, import_batches, "
            + "sale_items, sales, products, stores, businesses RESTART IDENTITY CASCADE";
}
