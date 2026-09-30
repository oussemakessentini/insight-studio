package com.oussamaksantini.insightstudio.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param cookieSecure {@code insight.security.cookie-secure}: mark the session and {@code XSRF-TOKEN}
 *     cookies {@code Secure}. Off by default for local http; turn it on wherever the app is served
 *     over HTTPS.
 */
@ConfigurationProperties("insight.security")
public record SecurityProperties(@DefaultValue("false") boolean cookieSecure) {
}
