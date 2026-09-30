package com.oussamaksantini.insightstudio.account;

/**
 * Delivers a password reset link. The link carries a secret, single-use token: implementations
 * must send it only to the account's email address and must not log or store it elsewhere.
 */
public interface PasswordResetNotifier {

    void sendResetLink(String email, String displayName, String resetLink);
}
