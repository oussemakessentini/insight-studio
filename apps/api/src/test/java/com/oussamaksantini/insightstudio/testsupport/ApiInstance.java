package com.oussamaksantini.insightstudio.testsupport;

import com.oussamaksantini.insightstudio.InsightApiApplication;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Another API instance (its own Spring context, server, connection pool and in-memory state) on
 * the test database: how tests show that sessions and rate limits are shared between instances
 * and survive a restart. Nothing in it is mocked.
 */
public final class ApiInstance implements AutoCloseable {

    private final ConfigurableApplicationContext context;

    private ApiInstance(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /** Starts an instance on a random port against the database described by {@code database}. */
    public static ApiInstance start(JdbcConnectionDetails database, Map<String, Object> extraProperties) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server.port", 0);
        properties.put("spring.datasource.url", database.getJdbcUrl());
        properties.put("spring.datasource.username", database.getUsername());
        properties.put("spring.datasource.password", database.getPassword());
        properties.put("spring.datasource.hikari.maximum-pool-size", 4);
        // Only the test's own configuration: never the developer's infra/.env.
        properties.put("spring.config.import", "");
        properties.putAll(extraProperties);
        // Command-line arguments: they take precedence over application.properties (builder
        // properties would only be defaults).
        String[] args = properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(InsightApiApplication.class).run(args);
        return new ApiInstance(context);
    }

    public static ApiInstance start(JdbcConnectionDetails database) {
        return start(database, Map.of());
    }

    public int port() {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    public HttpApiClient client() {
        return new HttpApiClient(port());
    }

    @Override
    public void close() {
        context.close();
    }
}
