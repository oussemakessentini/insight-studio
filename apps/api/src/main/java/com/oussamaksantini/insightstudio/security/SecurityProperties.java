package com.oussamaksantini.insightstudio.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param cookieSecure {@code insight.security.cookie-secure}: mark the session and {@code XSRF-TOKEN}
 *     cookies {@code Secure}. Off by default for local http; the {@code prod} profile requires it.
 * @param trustedProxies {@code insight.security.trusted-proxies}: IP addresses or CIDR ranges of the
 *     reverse proxies in front of the API, whose {@code X-Forwarded-For}/{@code X-Forwarded-Proto}
 *     headers are believed. Empty by default: forwarded headers are ignored.
 */
@ConfigurationProperties("insight.security")
public record SecurityProperties(
        @DefaultValue("false") boolean cookieSecure,
        @DefaultValue List<String> trustedProxies) {
}
