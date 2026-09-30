package com.oussamaksantini.insightstudio.importing;

import com.oussamaksantini.insightstudio.TestcontainersConfiguration;
import com.oussamaksantini.insightstudio.common.ImportsProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Base for tests that need the import endpoints, which exist only with
 * {@value ImportsProperties#ENABLED_PROPERTY}{@code =true}. This is a second Spring context (and
 * database container) next to {@code PostgresIntegrationTest}'s. It also starts a real server on a
 * random port, so upload size limits enforced by the servlet container can be tested over HTTP.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = ImportsProperties.ENABLED_PROPERTY + "=true")
abstract class ImportIntegrationTest {
}
