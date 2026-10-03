package com.oussamaksantini.insightstudio.account;

import com.oussamaksantini.insightstudio.mail.MailOutbox;
import java.time.Instant;

/** Queues the reset email in the {@link MailOutbox} (sent over SMTP; never logged). */
class EmailPasswordResetNotifier implements PasswordResetNotifier {

    private final MailOutbox outbox;

    EmailPasswordResetNotifier(MailOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void sendResetLink(long userId, String email, String displayName, String resetLink, Instant expiresAt) {
        outbox.enqueue("password reset", email, "Reset your Insight Studio password", """
                Hello %s,

                Someone (hopefully you) asked to reset the password of your Insight Studio account.
                To choose a new password, open this link within %d minutes:

                %s

                The link works once. If you didn't ask for this, ignore this email: your password
                stays the same.

                Insight Studio
                """.formatted(displayName, AccountService.RESET_TOKEN_LIFETIME.toMinutes(), resetLink), expiresAt, null, userId);
    }
}
