package com.oussamaksantini.insightstudio.account;

import com.oussamaksantini.insightstudio.mail.MailOutbox;
import java.time.Instant;

/** Queues the verification email in the {@link MailOutbox} (sent over SMTP; never logged). */
class EmailVerificationNotifier implements VerificationNotifier {

    private final MailOutbox outbox;

    EmailVerificationNotifier(MailOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void sendVerificationLink(String email, String displayName, String link, Instant expiresAt) {
        outbox.enqueue("email verification", email, "Verify your email for Insight Studio", """
                Hello %s,

                To finish setting up your Insight Studio account, confirm that this is your email
                address by opening this link within %d hours:

                %s

                The link works once. If you didn't create an account, ignore this email: nothing
                happens without the link.

                Insight Studio
                """.formatted(displayName, EmailVerificationService.TOKEN_LIFETIME.toHours(), link), expiresAt);
    }
}
