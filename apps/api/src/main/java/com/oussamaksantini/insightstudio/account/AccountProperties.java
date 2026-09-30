package com.oussamaksantini.insightstudio.account;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param webBaseUrl {@code insight.accounts.web-base-url} ({@code WEB_BASE_URL}): where the web app
 *     is served; links in account emails point to its pages ({@code /reset-password},
 *     {@code /invite}) with the token appended as {@code ?token=...}
 */
@ConfigurationProperties("insight.accounts")
public record AccountProperties(@DefaultValue("http://localhost:5173") String webBaseUrl) {

    public AccountProperties {
        URI uri;
        try {
            uri = URI.create(webBaseUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("insight.accounts.web-base-url is not a URL: " + webBaseUrl, e);
        }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException(
                    "insight.accounts.web-base-url must be an http(s) URL without query or fragment: " + webBaseUrl);
        }
        webBaseUrl = webBaseUrl.strip().replaceAll("/+$", "");
    }

    public boolean usesHttps() {
        return webBaseUrl.startsWith("https://");
    }

    /** A link to {@code page} (e.g. {@code /sign-in}) of the web app. */
    public String page(String page) {
        return webBaseUrl + page;
    }

    /** A link to {@code page} (e.g. {@code /reset-password}) carrying {@code token}. */
    public String link(String page, String token) {
        return webBaseUrl + page + "?token=" + token;
    }
}
