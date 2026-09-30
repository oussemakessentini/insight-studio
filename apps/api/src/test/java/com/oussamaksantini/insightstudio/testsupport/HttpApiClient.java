package com.oussamaksantini.insightstudio.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A browser-like client for the real server: keeps the cookies the API sets (session and
 * {@code XSRF-TOKEN}) and, like the web app, echoes the CSRF cookie in {@code X-XSRF-TOKEN} on
 * writes. No authentication is mocked.
 */
public final class HttpApiClient implements AutoCloseable {

    private final HttpClient http = HttpClient.newHttpClient();
    private final String base;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    public HttpApiClient(int port) {
        this.base = "http://localhost:" + port;
    }

    public HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(request(path).GET(), null);
    }

    public HttpResponse<String> get(String path, String header, String value) throws IOException, InterruptedException {
        return send(request(path).header(header, value).GET(), null);
    }

    /** POSTs JSON with the CSRF header (when the client has the cookie). */
    public HttpResponse<String> postJson(String path, String json) throws IOException, InterruptedException {
        return send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)), csrfToken());
    }

    /** POSTs JSON without any CSRF header. */
    public HttpResponse<String> postJsonWithoutCsrf(String path, String json) throws IOException, InterruptedException {
        return send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)), null);
    }

    /** POSTs a multipart body (built by the caller) with the CSRF header and optional extra headers. */
    public HttpResponse<String> postMultipart(String path, String boundary, byte[] body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = request(path)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return send(builder, csrfToken());
    }

    public static byte[] multipartFile(String boundary, String fileName, byte[] content) {
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: text/csv\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
        return body;
    }

    public String csrfToken() {
        return cookies.get("XSRF-TOKEN");
    }

    public String cookie(String name) {
        return cookies.get(name);
    }

    public Map<String, String> cookies() {
        return Map.copyOf(cookies);
    }

    /** Replaces this client's cookies, e.g. to replay a stolen or older session. */
    public void setCookies(Map<String, String> values) {
        cookies.clear();
        cookies.putAll(values);
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).header("Accept", "application/json");
        if (!cookies.isEmpty()) {
            builder.header("Cookie", cookies.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; ")));
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, String csrf) throws IOException, InterruptedException {
        if (csrf != null) {
            builder.header("X-XSRF-TOKEN", csrf);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        List<String> setCookies = response.headers().allValues("Set-Cookie");
        for (String header : setCookies) {
            String[] parts = header.split(";");
            String[] pair = parts[0].split("=", 2);
            String name = pair[0].strip();
            String value = pair.length > 1 ? pair[1].strip() : "";
            boolean expired = value.isEmpty();
            for (String attribute : parts) {
                String a = attribute.strip().toLowerCase(java.util.Locale.ROOT);
                if (a.equals("max-age=0") || a.startsWith("expires=thu, 01 jan 1970")) {
                    expired = true;
                }
            }
            if (expired) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
        return response;
    }

    @Override
    public void close() {
        http.close();
    }
}
