package com.oussamaksantini.insightstudio.security;

import com.oussamaksantini.insightstudio.account.AccountPrincipal;
import com.oussamaksantini.insightstudio.account.CurrentAccount;
import com.oussamaksantini.insightstudio.account.UserQueries;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects sessions made stale by a password change or reset: when the session's
 * {@link AccountPrincipal#sessionVersion()} differs from the stored {@code users.session_version}
 * (or the user no longer exists), the session is invalidated and the request continues as
 * anonymous, so protected endpoints answer 401.
 */
class SessionVersionFilter extends OncePerRequestFilter {

    private final UserQueries users;

    SessionVersionFilter(UserQueries users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<AccountPrincipal> principal = CurrentAccount.get();
        if (principal.isPresent()) {
            Optional<Integer> stored = users.sessionVersion(principal.get().userId());
            if (stored.isEmpty() || stored.get() != principal.get().sessionVersion()) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
