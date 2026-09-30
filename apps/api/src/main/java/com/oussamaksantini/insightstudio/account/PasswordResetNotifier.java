package com.oussamaksantini.insightstudio.account;

import java.time.Instant;

/**
 * Delivers a password reset link. The link carries a secret, single-use token: implementations
 * must send it only to the account's email address and must not log or store it elsewhere.
 */
public interface PasswordResetNotifier {

    /** Called inside the transaction that stores the token, so the email exists exactly when it does. */
    void sendResetLink(String email, String displayName, String resetLink, Instant expiresAt);
}
