package com.oussamaksantini.insightstudio.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.account.PasswordResetNotifier;
import com.oussamaksantini.insightstudio.account.VerificationNotifier;
import com.oussamaksantini.insightstudio.business.InvitationNotifier;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The real notifiers record which business or account an email belongs to
 * ({@code mail_outbox.business_id} / {@code user_id}), so deleting either can expire it.
 */
class MailOwnershipIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    @Qualifier("invitationNotifier")
    InvitationNotifier invitations;

    @Autowired
    @Qualifier("passwordResetNotifier")
    PasswordResetNotifier resets;

    @Autowired
    @Qualifier("verificationNotifier")
    VerificationNotifier verifications;

    @Test
    void invitationAndAccountEmailsKnowTheirOwner() {
        new SqlFixture(jdbc).clear();
        Instant later = Instant.now().plusSeconds(600);
        transactions.executeWithoutResult(status -> {
            invitations.sendInvitation(42, "invitee@example.com", "Owner", "Co", Role.VIEWER, "http://x/invite?token=t", later);
            resets.sendResetLink(7, "user@example.com", "User", "http://x/reset?token=t", later);
            verifications.sendVerificationLink(8, "new@example.com", "New", "http://x/verify?token=t", later);
            verifications.sendExistingAccountNotice(9, "old@example.com", "Old", "http://x/sign-in", "http://x/forgot");
        });
        Map<String, Object> invitation = jdbc.queryForMap("SELECT business_id, user_id FROM mail_outbox WHERE kind = 'invitation'");
        assertThat(((Number) invitation.get("business_id")).longValue()).isEqualTo(42);
        assertThat(invitation.get("user_id")).isNull();
        assertThat(jdbc.queryForList("SELECT user_id FROM mail_outbox WHERE kind <> 'invitation' ORDER BY id", Long.class))
                .containsExactly(7L, 8L, 9L);
    }
}
