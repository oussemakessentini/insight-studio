package com.oussamaksantini.insightstudio.security;

import com.oussamaksantini.insightstudio.account.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;

/**
 * Keeps the signed-in account in the HTTP session as three plain attributes (user id, email,
 * session version) instead of a serialized {@code SecurityContext}. Sessions live in PostgreSQL
 * (Spring Session JDBC) and are read by every API instance, including ones running a newer
 * version of the code: plain Java types always deserialize, framework classes might not.
 *
 * <p>It also sets Spring Session's principal index, so a user's sessions can be found (and ended)
 * by {@link AccountPrincipal#getName()}.
 */
final class SessionAccountContextRepository implements SecurityContextRepository {

    static final String USER_ID = "insight.account.userId";
    static final String EMAIL = "insight.account.email";
    static final String SESSION_VERSION = "insight.account.sessionVersion";

    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return new Deferred(() -> load(request));
    }

    @Override
    @Deprecated
    public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        SecurityContext context = load(holder.getRequest());
        return context == null ? this.holder.createEmptyContext() : context;
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = context.getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            HttpSession session = request.getSession(true);
            session.setAttribute(USER_ID, principal.userId());
            session.setAttribute(EMAIL, principal.email());
            session.setAttribute(SESSION_VERSION, principal.sessionVersion());
            session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principal.getName());
            return;
        }
        // Anything else (signing out, an anonymous context) forgets the account, never creating a session.
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(USER_ID);
            session.removeAttribute(EMAIL);
            session.removeAttribute(SESSION_VERSION);
            session.removeAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
        }
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(USER_ID) != null;
    }

    /** The session's account as an authenticated context, or null when the session has none. */
    private SecurityContext load(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        if (!(session.getAttribute(USER_ID) instanceof Long userId)
                || !(session.getAttribute(EMAIL) instanceof String email)
                || !(session.getAttribute(SESSION_VERSION) instanceof Integer version)) {
            return null;
        }
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication(new AccountPrincipal(userId, email, version)));
        return context;
    }

    static Authentication authentication(AccountPrincipal principal) {
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private final class Deferred implements DeferredSecurityContext {

        private final Supplier<SecurityContext> loader;
        private SecurityContext context;
        private boolean generated;

        Deferred(Supplier<SecurityContext> loader) {
            this.loader = loader;
        }

        @Override
        public SecurityContext get() {
            if (context == null) {
                SecurityContext loaded = loader.get();
                generated = loaded == null;
                context = loaded == null ? holder.createEmptyContext() : loaded;
            }
            return context;
        }

        @Override
        public boolean isGenerated() {
            get();
            return generated;
        }
    }
}
