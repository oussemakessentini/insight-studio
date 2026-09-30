package com.oussamaksantini.insightstudio.account;

import java.time.Instant;

/**
 * Delivers an email-verification link. The link carries a secret, single-use token:
 * implementations must send it only to the account's address and must not log or store it
 * elsewhere. Called inside the transaction that stores the token.
 */
public interface VerificationNotifier {

    void sendVerificationLink(String email, String displayName, String link, Instant expiresAt);
}
