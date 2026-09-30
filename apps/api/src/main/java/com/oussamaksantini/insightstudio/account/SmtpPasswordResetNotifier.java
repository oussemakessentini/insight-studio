package com.oussamaksantini.insightstudio.account;

import com.oussamaksantini.insightstudio.mail.MailDelivery;

/** Emails the reset link through {@link MailDelivery} (SMTP; asynchronous; never logged). */
class SmtpPasswordResetNotifier implements PasswordResetNotifier {

    private final MailDelivery mail;

    SmtpPasswordResetNotifier(MailDelivery mail) {
        this.mail = mail;
    }

    @Override
    public void sendResetLink(String email, String displayName, String resetLink) {
        mail.send("password reset", email, "Reset your Insight Studio password", """
                Hello %s,

                Someone (hopefully you) asked to reset the password of your Insight Studio account.
                To choose a new password, open this link within %d minutes:

                %s

                The link works once. If you didn't ask for this, ignore this email: your password
                stays the same.

                Insight Studio
                """.formatted(displayName, AccountService.RESET_TOKEN_LIFETIME.toMinutes(), resetLink));
    }
}
