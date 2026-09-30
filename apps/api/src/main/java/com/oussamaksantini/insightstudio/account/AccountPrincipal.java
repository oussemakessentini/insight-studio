package com.oussamaksantini.insightstudio.account;

import java.io.Serializable;
import java.security.Principal;

/**
 * The signed-in user held in the session's security context.
 *
 * @param sessionVersion the user's {@code session_version} when this session signed in; a password
 *     change or reset increments the stored value, and a session carrying an older value is rejected
 */
public record AccountPrincipal(long userId, String email, int sessionVersion) implements Principal, Serializable {

    @Override
    public String getName() {
        return "user:" + userId;
    }

    public AccountPrincipal withSessionVersion(int version) {
        return new AccountPrincipal(userId, email, version);
    }
}
