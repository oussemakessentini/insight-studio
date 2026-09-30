package com.oussamaksantini.insightstudio.account;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The signed-in account of the current request, if any (anonymous and demo requests have none). */
public final class CurrentAccount {

    private CurrentAccount() {
    }

    public static Optional<AccountPrincipal> get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }
}
