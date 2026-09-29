package com.oussamaksantini.insightstudio;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Base for tests that need the full application and a real database. Sharing one configuration
 * means Spring caches a single context, so only one PostgreSQL container starts per test run.
 * Tests must set up (and may truncate) the data they rely on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class PostgresIntegrationTest {

    protected static final String TRUNCATE_ALL =
            "TRUNCATE sale_items, sales, products, stores, businesses RESTART IDENTITY CASCADE";
}
