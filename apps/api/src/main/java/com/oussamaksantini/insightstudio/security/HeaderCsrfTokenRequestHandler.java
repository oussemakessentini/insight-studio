package com.oussamaksantini.insightstudio.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * SPA CSRF handling: the token lives in the readable {@code XSRF-TOKEN} cookie and must come back
 * in the {@code X-XSRF-TOKEN} header. The token is loaded on every request, so any response to a
 * client without the cookie issues one.
 *
 * <p>Only the header is accepted (never a {@code _csrf} form parameter): reading parameters would
 * make the filter parse request bodies, including large multipart uploads, before authorization.
 * The token never appears in a response body, so BREACH masking is unnecessary.
 */
final class HeaderCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        super.handle(request, response, csrfToken);
        // Load the deferred token now so the cookie is written when the client has none.
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        return request.getHeader(csrfToken.getHeaderName());
    }
}
