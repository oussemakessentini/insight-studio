package com.oussamaksantini.insightstudio.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the client address (used for rate limits) and scheme behind reverse proxies, trusting
 * forwarded headers only from configured proxies ({@code insight.security.trusted-proxies}).
 *
 * <ul>
 *   <li>If the TCP peer is not a trusted proxy (or none are configured), {@code X-Forwarded-For}
 *       and {@code X-Forwarded-Proto} are ignored: anyone can send them.</li>
 *   <li>Otherwise {@code X-Forwarded-For} is read right to left, skipping trusted proxies; the first
 *       address that is not one is the client. Entries left of it were written by the client (or
 *       an untrusted hop) and are ignored, so a spoofed header cannot choose the address.</li>
 *   <li>{@code X-Forwarded-Proto: https} from a trusted proxy marks the request secure (HSTS).</li>
 * </ul>
 */
final class TrustedProxyFilter extends OncePerRequestFilter {

    static final String FORWARDED_FOR = "X-Forwarded-For";
    static final String FORWARDED_PROTO = "X-Forwarded-Proto";
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");
    private static final int MAX_HOPS = 20;

    private final List<IpAddressMatcher> proxies;

    TrustedProxyFilter(List<String> trustedProxies) {
        List<IpAddressMatcher> matchers = new ArrayList<>();
        for (String proxy : trustedProxies) {
            String entry = proxy.strip();
            if (entry.isEmpty()) {
                continue;
            }
            String address = entry.contains("/") ? entry.substring(0, entry.indexOf('/')) : entry;
            if (parseAddress(address) == null) {
                throw new IllegalStateException("insight.security.trusted-proxies: '" + entry
                        + "' is not an IP address or CIDR range (host names are not accepted).");
            }
            matchers.add(new IpAddressMatcher(entry));
        }
        this.proxies = List.copyOf(matchers);
    }

    boolean hasTrustedProxies() {
        return !proxies.isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String peer = parseAddress(request.getRemoteAddr());
        if (proxies.isEmpty() || peer == null || !trusted(peer)) {
            chain.doFilter(request, response);
            return;
        }
        String client = clientAddress(peer, Collections.list(request.getHeaders(FORWARDED_FOR)));
        String proto = lastValue(Collections.list(request.getHeaders(FORWARDED_PROTO)));
        Boolean secure = proto == null ? null : proto.equalsIgnoreCase("https");
        chain.doFilter(new ForwardedRequest(request, client, secure), response);
    }

    /** The client behind trusted proxies: the right-most forwarded address that is not a trusted proxy. */
    String clientAddress(String peer, List<String> forwardedFor) {
        List<String> hops = new ArrayList<>();
        for (String header : forwardedFor) {
            for (String part : header.split(",")) {
                hops.add(part.strip());
            }
        }
        String current = peer;
        int seen = 0;
        for (int i = hops.size() - 1; i >= 0 && seen < MAX_HOPS; i--, seen++) {
            String address = parseAddress(hops.get(i));
            if (address == null) {
                // Malformed (or "unknown"): stop at the last trusted hop rather than guess.
                return current;
            }
            current = address;
            if (!trusted(address)) {
                return address;
            }
        }
        return current;
    }

    private boolean trusted(String address) {
        for (IpAddressMatcher proxy : proxies) {
            if (proxy.matches(address)) {
                return true;
            }
        }
        return false;
    }

    private static String lastValue(List<String> headers) {
        if (headers.isEmpty()) {
            return null;
        }
        String[] parts = headers.getLast().split(",");
        String value = parts[parts.length - 1].strip();
        return value.isEmpty() ? null : value;
    }

    /**
     * A normalized IP literal from {@code value} (optionally {@code [v6]}, {@code [v6]:port} or
     * {@code v4:port}), or null. Never resolves host names.
     */
    static String parseAddress(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.strip();
        if (candidate.startsWith("[")) {
            int end = candidate.indexOf(']');
            if (end < 0) {
                return null;
            }
            candidate = candidate.substring(1, end);
        } else if (candidate.chars().filter(c -> c == ':').count() == 1) {
            candidate = candidate.substring(0, candidate.indexOf(':'));
        }
        if (IPV4.matcher(candidate).matches()) {
            return candidate;
        }
        if (candidate.contains(":") && IPV6_CHARS.matcher(candidate).matches()) {
            try {
                // Only hex digits, dots and colons: parsed as a literal, no DNS lookup.
                return InetAddress.getByName(candidate).getHostAddress().toLowerCase(Locale.ROOT);
            } catch (UnknownHostException e) {
                return null;
            }
        }
        return null;
    }

    private static final class ForwardedRequest extends HttpServletRequestWrapper {

        private final String client;
        private final Boolean secure;

        ForwardedRequest(HttpServletRequest request, String client, Boolean secure) {
            super(request);
            this.client = client;
            this.secure = secure;
        }

        @Override
        public String getRemoteAddr() {
            return client;
        }

        @Override
        public String getRemoteHost() {
            return client;
        }

        @Override
        public boolean isSecure() {
            return secure == null ? super.isSecure() : secure;
        }

        @Override
        public String getScheme() {
            return secure == null ? super.getScheme() : secure ? "https" : "http";
        }
    }
}
