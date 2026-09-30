package com.oussamaksantini.insightstudio.account;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param resetLinkBase web app page that completes a password reset; the token is appended as
 *     {@code ?token=...}
 */
@ConfigurationProperties("insight.accounts")
public record AccountProperties(@DefaultValue("http://localhost:5173/reset-password") String resetLinkBase) {
}
