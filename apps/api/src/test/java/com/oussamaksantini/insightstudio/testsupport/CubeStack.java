package com.oussamaksantini.insightstudio.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.Container.ExecResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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
    /** Extra Cube environment (e.g. how quickly superseded rollup tables are dropped). */
    private final Map<String, String> extraCubeEnv;

    public CubeStack() {
        this(Map.of());
    }

    public CubeStack(Map<String, String> extraCubeEnv) {
        this.extraCubeEnv = new LinkedHashMap<>(extraCubeEnv);
    }

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
                .withEnv(extraCubeEnv)
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

    /**
     * Runs each of {@code sqls} on Cube Store and returns their rows ({@code null} for a statement that
     * failed, e.g. on a table dropped meanwhile). Cube Store has no JDBC driver on the test classpath,
     * so the statements run inside the Cube container with Cube's own Cube Store driver (the WebSocket
     * protocol on port 3030, exactly as Cube itself talks to Cube Store).
     */
    public List<List<JsonNode>> cubeStoreQueries(List<String> sqls) {
        String script = """
                const { CubeStoreDriver } = require('@cubejs-backend/cubestore-driver');
                const driver = new CubeStoreDriver({ host: 'cubestore', port: 3030 });
                (async () => {
                  const results = [];
                  for (const sql of JSON.parse(process.argv[1])) {
                    try { results.push(await driver.query(sql, [])); } catch (e) { results.push(null); }
                  }
                  process.stdout.write('ROWS' + JSON.stringify(results));
                  await driver.release();
                })().then(() => process.exit(0), (e) => { console.error(e.message); process.exit(1); });
                """;
        try {
            ExecResult result = cube.execInContainer("node", "-e", script, JSON.writeValueAsString(sqls));
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("Cube Store query failed: " + result.getStderr());
            }
            String out = result.getStdout();
            JsonNode all = JSON.readTree(out.substring(out.indexOf("ROWS") + 4));
            List<List<JsonNode>> results = new java.util.ArrayList<>();
            for (JsonNode rows : all) {
                if (rows.isNull()) {
                    results.add(null);
                } else {
                    List<JsonNode> list = new java.util.ArrayList<>();
                    rows.forEach(list::add);
                    results.add(list);
                }
            }
            return results;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public List<JsonNode> cubeStoreQuery(String sql) {
        List<JsonNode> rows = cubeStoreQueries(List.of(sql)).getFirst();
        if (rows == null) {
            throw new IllegalStateException("Cube Store query failed: " + sql);
        }
        return rows;
    }

    /** Every table in Cube's pre-aggregation schema ({@code prod_pre_aggregations}), as schema.table. */
    public List<String> preAggregationTables() {
        return cubeStoreQuery("SELECT table_schema, table_name FROM information_schema.tables").stream()
                .filter(t -> t.get("table_schema").asString().endsWith("pre_aggregations"))
                .map(t -> t.get("table_schema").asString() + "." + t.get("table_name").asString())
                .toList();
    }

    /**
     * The rows of {@code businessId} in every pre-aggregation table, by table: every rollup of the model
     * has its cube's {@code business_id} as a dimension (column {@code <cube>__business_id}). A table
     * dropped while this runs is left out.
     */
    public Map<String, Long> businessRowsPerTable(long businessId) {
        List<String> tables = preAggregationTables();
        List<String> sqls = tables.stream().map(table -> {
            String name = table.substring(table.indexOf('.') + 1);
            String cube = name.substring(0, name.indexOf("_daily_"));
            return "SELECT count(*) AS n FROM %s WHERE %s__business_id = %d".formatted(table, cube, businessId);
        }).toList();
        List<List<JsonNode>> counts = cubeStoreQueries(sqls);
        Map<String, Long> rows = new LinkedHashMap<>();
        for (int i = 0; i < tables.size(); i++) {
            if (counts.get(i) != null) {
                rows.put(tables.get(i), counts.get(i).getFirst().get("n").asLong());
            }
        }
        return rows;
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

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static byte[] random(int bytes) {
        byte[] value = new byte[bytes];
        new SecureRandom().nextBytes(value);
        return value;
    }
}
