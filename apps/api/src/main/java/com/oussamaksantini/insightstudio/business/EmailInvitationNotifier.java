package com.oussamaksantini.insightstudio.business;

import com.oussamaksantini.insightstudio.mail.MailOutbox;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Queues invitation emails in the {@link MailOutbox} (sent over SMTP; never logged). */
class EmailInvitationNotifier implements InvitationNotifier {

    private static final DateTimeFormatter EXPIRY =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final MailOutbox outbox;

    EmailInvitationNotifier(MailOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void sendInvitation(String email, String invitedBy, String businessName, Role role, String link, Instant expiresAt) {
        outbox.enqueue("invitation", email, "You're invited to join %s on Insight Studio".formatted(businessName), """
                Hello,

                %s invited you to join %s on Insight Studio as %s.

                To accept, open this link, then sign in or create an account with this email
                address (%s):

                %s

                The invitation works once and expires on %s. If you weren't expecting it, you can
                ignore this email.

                Insight Studio
                """.formatted(invitedBy, businessName, article(role), email, link, EXPIRY.format(expiresAt)), expiresAt);
    }

    private static String article(Role role) {
        return switch (role) {
            case OWNER -> "an owner";
            case ADMIN -> "an admin";
            case VIEWER -> "a viewer";
        };
    }
}
