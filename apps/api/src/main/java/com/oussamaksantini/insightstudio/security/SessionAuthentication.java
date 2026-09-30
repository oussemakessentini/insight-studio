package com.oussamaksantini.insightstudio.security;

import com.oussamaksantini.insightstudio.account.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * Starts, refreshes and ends server-side sessions for the account endpoints. Signing in always
 * changes the session id (session fixation protection) and issues a new CSRF token.
 */
@Component
public class SessionAuthentication {

    static final String SESSION_COOKIE = "JSESSIONID";

    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfTokens;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
    private final ChangeSessionIdAuthenticationStrategy sessionFixation = new ChangeSessionIdAuthenticationStrategy();

    SessionAuthentication(SecurityContextRepository contextRepository, CsrfTokenRepository csrfTokens) {
        this.contextRepository = contextRepository;
        this.csrfTokens = csrfTokens;
    }

    /** Signs {@code principal} in on this request's session (created or with a new id). */
    public void signIn(AccountPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = authentication(principal);
        sessionFixation.onAuthentication(authentication, request, response);
        save(authentication, request, response);
        rotateCsrfToken(request, response);
    }

    /**
     * Replaces the principal of the current session (after a password change) with a new session
     * id, keeping the user signed in on this session only.
     */
    public void refresh(AccountPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        signIn(principal, request, response);
    }

    /** Invalidates the session, clears the session cookie and issues a fresh CSRF token. */
    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = holder.getContext().getAuthentication();
        SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();
        logout.setSecurityContextRepository(contextRepository);
        logout.logout(request, response, authentication);
        new CookieClearingLogoutHandler(SESSION_COOKIE).logout(request, response, authentication);
        rotateCsrfToken(request, response);
    }

    private void save(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication);
        holder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokens.generateToken(request);
        csrfTokens.saveToken(token, request, response);
        request.setAttribute(CsrfToken.class.getName(), token);
        request.setAttribute(token.getParameterName(), token);
    }

    private static Authentication authentication(AccountPrincipal principal) {
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
