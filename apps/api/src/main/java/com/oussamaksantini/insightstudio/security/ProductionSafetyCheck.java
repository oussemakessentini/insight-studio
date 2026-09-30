package com.oussamaksantini.insightstudio.security;

import com.oussamaksantini.insightstudio.account.AccountProperties;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * With the {@code prod} profile, refuses to start unless the API is set up to be served over
 * HTTPS: Secure session and CSRF cookies, and an {@code https://} web address for email links.
 * application-prod.properties sets these; this check stops an environment variable (e.g.
 * {@code COOKIE_SECURE=false}) from quietly undoing them.
 */
@Component
@Profile("prod")
class ProductionSafetyCheck {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyCheck.class);

    ProductionSafetyCheck(SecurityProperties security, AccountProperties accounts, Environment environment) {
        List<String> problems = new ArrayList<>();
        if (!security.cookieSecure()) {
            problems.add("insight.security.cookie-secure (COOKIE_SECURE) must be true");
        }
        if (!Boolean.TRUE.equals(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class))) {
            problems.add("server.servlet.session.cookie.secure must be true");
        }
        if (!accounts.usesHttps()) {
            problems.add("insight.accounts.web-base-url (WEB_BASE_URL) must be an https:// address");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The prod profile requires HTTPS settings (docs/production.md): "
                    + String.join("; ", problems) + ".");
        }
        if (security.trustedProxies().isEmpty()) {
            log.warn("No trusted proxies configured (TRUSTED_PROXIES): behind a reverse proxy, every client "
                    + "shares the proxy's address for rate limits and HSTS is not sent.");
        }
    }
}
