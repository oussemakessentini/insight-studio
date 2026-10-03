package com.oussamaksantini.insightstudio.account;

import java.time.Instant;

/**
 * Emails sent in answer to a sign-up. Called inside the sign-up's transaction.
 *
 * <p>A verification link carries a secret, single-use token: implementations must send it only to
 * the account's address and must not log or store it elsewhere.
 */
public interface VerificationNotifier {

    /** A new account's link to verify its address. */
    void sendVerificationLink(long userId, String email, String displayName, String link, Instant expiresAt);

    /**
     * Someone signed up with an address that already has an account: tell its owner (who may have
     * forgotten) how to sign in or reset the password. Carries no token.
     */
    void sendExistingAccountNotice(long userId, String email, String displayName, String signInLink, String resetLink);
}
