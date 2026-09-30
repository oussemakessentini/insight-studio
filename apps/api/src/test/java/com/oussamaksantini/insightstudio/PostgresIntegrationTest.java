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
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "insight.mail.outbox.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestSupportConfiguration.class})
public abstract class PostgresIntegrationTest {

    protected static final String TRUNCATE_ALL = "TRUNCATE mail_outbox, spring_session, rate_limit_hits, invitations, password_reset_tokens, memberships, users, import_batches, "
            + "sale_items, sales, products, stores, businesses RESTART IDENTITY CASCADE";
}
