package com.oussamaksantini.insightstudio.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL 16, Cube Store and Cube (the images of infra/compose.yaml) on one Docker network, with
 * the repository's {@code services/analytics} model mounted read-only, Cube in production mode and
 * its refresh worker on. Only random host ports are used.
 *
 * <p>Start order matters: {@link #startDatabase()} first, then an API instance (Flyway creates the
 * schema, including {@code report_data_version}), then {@link #startCube()}.
 */
public final class CubeStack implements AutoCloseable {

    /** Business time zones the refresh worker builds rollups for ahead of time. */
    public static final String REFRESH_TIME_ZONES = "America/New_York,Europe/Paris,Pacific/Auckland,Asia/Kolkata,UTC";

    private static final int CUBE_PORT = 4000;

    private final Network network = Network.newNetwork();
    /** A fresh secret per run, shared by Cube and the API instances; never logged or committed. */
    private final String secret = HexFormat.of().formatHex(random(24));
    private final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withNetwork(network)
            .withNetworkAliases("db")
            .withStartupTimeout(Duration.ofMinutes(3));
    private GenericContainer<?> cubeStore;
    private GenericContainer<?> cube;

    public void startDatabase() {
        postgres.start();
    }

    /** Starts Cube Store and Cube; the database schema must exist already. */
    public void startCube() {
        Path model = Path.of("../../services/analytics").toAbsolutePath().normalize();
        if (!Files.isRegularFile(model.resolve("cube.js"))) {
            throw new IllegalStateException("services/analytics not found at " + model + " (run the tests from apps/api)");
        }
        cubeStore = new GenericContainer<>(DockerImageName.parse("cubejs/cubestore:v1.7.46"))
                .withNetwork(network)
                .withNetworkAliases("cubestore")
                .withEnv("CUBESTORE_REMOTE_DIR", "/cube/data")
                .withEnv("CUBESTORE_TELEMETRY", "false")
                .withExposedPorts(3030)
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(3));
        cubeStore.start();
        cube = new GenericContainer<>(DockerImageName.parse("cubejs/cube:v1.7.46"))
                .withNetwork(network)
                .withEnv("CUBEJS_DB_TYPE", "postgres")
                .withEnv("CUBEJS_DB_HOST", "db")
                .withEnv("CUBEJS_DB_PORT", "5432")
                .withEnv("CUBEJS_DB_NAME", postgres.getDatabaseName())
                .withEnv("CUBEJS_DB_USER", postgres.getUsername())
                .withEnv("CUBEJS_DB_PASS", postgres.getPassword())
                .withEnv("CUBEJS_API_SECRET", secret)
                .withEnv("CUBEJS_DEV_MODE", "false")
                .withEnv("NODE_ENV", "production")
                .withEnv("CUBEJS_REFRESH_WORKER", "true")
                .withEnv("CUBEJS_CUBESTORE_HOST", "cubestore")
                .withEnv("CUBEJS_SCHEDULED_REFRESH_TIMEZONES", REFRESH_TIME_ZONES)
                .withEnv("CUBEJS_TELEMETRY", "false")
                .withFileSystemBind(model.toString(), "/cube/conf", BindMode.READ_ONLY)
                .withExposedPorts(CUBE_PORT)
                .waitingFor(Wait.forHttp("/readyz").forPort(CUBE_PORT).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(5));
        cube.start();
    }

    /** Stops Cube (and only Cube): its port then refuses connections. */
    public void stopCube() {
        cube.stop();
    }

    public String cubeUrl() {
        return "http://" + cube.getHost() + ":" + cube.getMappedPort(CUBE_PORT);
    }

    public String secret() {
        return secret;
    }

    public JdbcConnectionDetails database() {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return postgres.getUsername();
            }

            @Override
            public String getPassword() {
                return postgres.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }
        };
    }

    /** Cube's recent log, for diagnosing a failed test (contains no secrets). */
    public String cubeLogs() {
        return cube == null ? "" : cube.getLogs();
    }

    @Override
    public void close() {
        if (cube != null) {
            cube.stop();
        }
        if (cubeStore != null) {
            cubeStore.stop();
        }
        postgres.stop();
        network.close();
    }

    private static byte[] random(int bytes) {
        byte[] value = new byte[bytes];
        new SecureRandom().nextBytes(value);
        return value;
    }
}
