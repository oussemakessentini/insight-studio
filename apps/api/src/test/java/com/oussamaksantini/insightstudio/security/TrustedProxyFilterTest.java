package com.oussamaksantini.insightstudio.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TrustedProxyFilterTest {

    private static final TrustedProxyFilter NONE = new TrustedProxyFilter(List.of());
    private static final TrustedProxyFilter PROXIES = new TrustedProxyFilter(List.of("10.0.0.0/8", "192.0.2.10", "fd00::/8"));

    /** The request as the rest of the application sees it. */
    private static HttpServletRequest seen(TrustedProxyFilter filter, String peer, String forwardedFor, String proto)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/session");
        request.setRemoteAddr(peer);
        if (forwardedFor != null) {
            request.addHeader(TrustedProxyFilter.FORWARDED_FOR, forwardedFor);
        }
        if (proto != null) {
            request.addHeader(TrustedProxyFilter.FORWARDED_PROTO, proto);
        }
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));
        return seen.get();
    }

    @Test
    void withoutTrustedProxiesForwardedHeadersAreIgnored() throws Exception {
        HttpServletRequest request = seen(NONE, "10.1.2.3", "203.0.113.7", "https");
        assertThat(request.getRemoteAddr()).isEqualTo("10.1.2.3");
        assertThat(request.isSecure()).isFalse();
    }

    @Test
    void headersFromAnUntrustedPeerAreIgnored() throws Exception {
        HttpServletRequest request = seen(PROXIES, "198.51.100.4", "203.0.113.7", "https");
        assertThat(request.getRemoteAddr()).isEqualTo("198.51.100.4");
        assertThat(request.isSecure()).isFalse();
        assertThat(request.getScheme()).isEqualTo("http");
    }

    @Test
    void theClientIsTheRightMostUntrustedAddress() throws Exception {
        // The client sent a spoofed first entry; the proxy appended the address it saw.
        assertThat(seen(PROXIES, "10.0.0.5", "1.1.1.1, 203.0.113.7", null).getRemoteAddr()).isEqualTo("203.0.113.7");
        // Two trusted proxies in a row (10.x, then 192.0.2.10 which connected to us).
        assertThat(seen(PROXIES, "192.0.2.10", "203.0.113.7, 10.0.0.9", null).getRemoteAddr()).isEqualTo("203.0.113.7");
        // Several header lines count as one list.
        assertThat(PROXIES.clientAddress("10.0.0.5", List.of("6.6.6.6", "203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void portsBracketsAndIpv6AreUnderstood() throws Exception {
        assertThat(seen(PROXIES, "10.0.0.5", "203.0.113.7:51234", null).getRemoteAddr()).isEqualTo("203.0.113.7");
        assertThat(seen(PROXIES, "10.0.0.5", "[2001:db8::1]:443", null).getRemoteAddr()).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(seen(PROXIES, "fd00::2", "2001:db8::1", null).getRemoteAddr()).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void malformedEntriesStopAtTheLastTrustedHop() throws Exception {
        assertThat(seen(PROXIES, "10.0.0.5", "203.0.113.7, unknown", null).getRemoteAddr()).isEqualTo("10.0.0.5");
        assertThat(seen(PROXIES, "10.0.0.5", "evil.example.com", null).getRemoteAddr()).isEqualTo("10.0.0.5");
        assertThat(seen(PROXIES, "10.0.0.5", "999.1.1.1", null).getRemoteAddr()).isEqualTo("10.0.0.5");
        // Only trusted proxies in the list: the left-most of them.
        assertThat(seen(PROXIES, "10.0.0.5", "10.0.0.7", null).getRemoteAddr()).isEqualTo("10.0.0.7");
        // No header at all: the proxy itself.
        assertThat(seen(PROXIES, "10.0.0.5", null, null).getRemoteAddr()).isEqualTo("10.0.0.5");
    }

    @Test
    void theSchemeComesFromATrustedProxyOnly() throws Exception {
        HttpServletRequest request = seen(PROXIES, "10.0.0.5", "203.0.113.7", "https");
        assertThat(request.isSecure()).isTrue();
        assertThat(request.getScheme()).isEqualTo("https");
        assertThat(seen(PROXIES, "10.0.0.5", "203.0.113.7", "http").isSecure()).isFalse();
    }

    @Test
    void hostNamesAreRejectedInTheConfiguration() {
        assertThatThrownBy(() -> new TrustedProxyFilter(List.of("proxy.internal")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an IP address or CIDR range");
        assertThat(new TrustedProxyFilter(List.of(" ", "")).hasTrustedProxies()).isFalse();
    }

    @Test
    void parsesOnlyLiterals() {
        assertThat(TrustedProxyFilter.parseAddress("127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(TrustedProxyFilter.parseAddress("::1")).isEqualTo("0:0:0:0:0:0:0:1");
        assertThat(TrustedProxyFilter.parseAddress("localhost")).isNull();
        assertThat(TrustedProxyFilter.parseAddress("1.2.3")).isNull();
        assertThat(TrustedProxyFilter.parseAddress("[::1")).isNull();
        assertThat(TrustedProxyFilter.parseAddress("")).isNull();
    }
}
