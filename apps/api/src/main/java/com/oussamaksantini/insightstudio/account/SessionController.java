package com.oussamaksantini.insightstudio.account;

import com.oussamaksantini.insightstudio.account.dto.DemoInfo;
import com.oussamaksantini.insightstudio.account.dto.MembershipInfo;
import com.oussamaksantini.insightstudio.account.dto.SessionResponse;
import com.oussamaksantini.insightstudio.tenancy.Memberships;
import com.oussamaksantini.insightstudio.tenancy.PublicDemo;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/session}: public. Tells the web app who is signed in, their businesses and
 * whether the public demo is available, and always sends the {@code XSRF-TOKEN} cookie.
 */
@RestController
class SessionController {

    private final AccountService accounts;
    private final Memberships memberships;
    private final PublicDemo demo;
    private final CsrfTokenRepository csrfTokens;

    SessionController(AccountService accounts, Memberships memberships, PublicDemo demo, CsrfTokenRepository csrfTokens) {
        this.accounts = accounts;
        this.memberships = memberships;
        this.demo = demo;
        this.csrfTokens = csrfTokens;
    }

    @GetMapping("/api/session")
    SessionResponse session(CsrfToken csrfToken, HttpServletRequest request, HttpServletResponse response) {
        issueCsrfCookie(csrfToken, request, response);
        DemoInfo demoInfo = demo.find().map(d -> new DemoInfo(true, d.businessId(), d.name())).orElse(null);
        return CurrentAccount.get()
                .flatMap(principal -> accounts.find(principal.userId()))
                .map(user -> new SessionResponse(true, AuthController.info(user), memberships(user.id()), demoInfo))
                .orElseGet(() -> new SessionResponse(false, null, List.of(), demoInfo));
    }

    /**
     * The CSRF handler writes the cookie when the client has none; when it already has one, send it
     * again so this endpoint reliably (re)establishes it.
     */
    private void issueCsrfCookie(CsrfToken csrfToken, HttpServletRequest request, HttpServletResponse response) {
        Cookie[] cookies = request.getCookies();
        boolean clientHasCookie = cookies != null && Arrays.stream(cookies).anyMatch(c -> "XSRF-TOKEN".equals(c.getName()));
        if (csrfToken != null && clientHasCookie) {
            csrfTokens.saveToken(csrfToken, request, response);
        }
    }

    private List<MembershipInfo> memberships(long userId) {
        return memberships.forUser(userId).stream()
                .map(m -> new MembershipInfo(m.businessId(), m.name(), m.slug(), m.role()))
                .toList();
    }
}
