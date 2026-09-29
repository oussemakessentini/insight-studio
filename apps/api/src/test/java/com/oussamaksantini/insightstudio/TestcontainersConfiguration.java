package com.oussamaksantini.insightstudio;

import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Runs tests against a throwaway PostgreSQL matching infra/compose.yaml. Requires Docker. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        // Docker Desktop on Windows can take well over the default 60 s to start a container.
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withStartupTimeout(Duration.ofMinutes(3));
    }
}
