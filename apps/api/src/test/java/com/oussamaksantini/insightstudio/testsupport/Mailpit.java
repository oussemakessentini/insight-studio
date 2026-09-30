package com.oussamaksantini.insightstudio.testsupport;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A throwaway Mailpit (the development mail catcher from infra/compose.yaml) for tests that send
 * real SMTP, with helpers to read what arrived through its HTTP API.
 */
public final class Mailpit implements AutoCloseable {

    public static final String IMAGE = "axllent/mailpit:v1.31.3";
    private static final int SMTP = 1025;
    private static final int HTTP = 8025;

    private final GenericContainer<?> container;
    private final HttpClient http = HttpClient.newHttpClient();

    public record Message(String id, String from, String to, String subject, String text) {
    }

    @SuppressWarnings("resource")
    public Mailpit() {
        container = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(SMTP, HTTP)
                .waitingFor(Wait.forHttp("/livez").forPort(HTTP))
                .withStartupTimeout(Duration.ofMinutes(3));
        container.start();
    }

    public String host() {
        return container.getHost();
    }

    public int smtpPort() {
        return container.getMappedPort(SMTP);
    }

    /** Spring properties pointing the API's mail at this server. */
    public Map<String, Object> apiProperties() {
        return Map.of("spring.mail.host", host(), "spring.mail.port", smtpPort());
    }

    public void clear() throws IOException, InterruptedException {
        http.send(request("/api/v1/messages").DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    public List<Message> messages() throws IOException, InterruptedException {
        String list = http.send(request("/api/v1/messages").GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        List<String> ids = JsonPath.read(list, "$.messages[*].ID");
        return ids.stream().map(this::message).toList();
    }

    /** Waits (mail is sent asynchronously) until {@code count} messages have arrived. */
    public List<Message> awaitMessages(int count) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (true) {
            List<Message> messages = messages();
            if (messages.size() >= count || Instant.now().isAfter(deadline)) {
                return messages;
            }
            Thread.sleep(100);
        }
    }

    private Message message(String id) {
        try {
            String body = http.send(request("/api/v1/message/" + id).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            return new Message(id,
                    JsonPath.read(body, "$.From.Address"),
                    JsonPath.read(body, "$.To[0].Address"),
                    JsonPath.read(body, "$.Subject"),
                    JsonPath.read(body, "$.Text"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://" + host() + ":" + container.getMappedPort(HTTP) + path));
    }

    @Override
    public void close() {
        http.close();
        container.stop();
    }
}
