package com.oussamaksantini.insightstudio.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Responses captured from a real Cube v1.7.46 in production mode ({@code src/test/resources/cube}):
 * the model in services/analytics, business 1 in Europe/Paris, data version 7 (9 after a change).
 */
public final class CubeFixtures {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CubeFixtures() {
    }

    /** The captured {@code /load} body {@code name}, parsed like the API's Cube client does. */
    public static Map<?, ?> body(String name) {
        try (InputStream in = CubeFixtures.class.getResourceAsStream("/cube/" + name + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("No Cube fixture " + name);
            }
            return JSON.readValue(in, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
