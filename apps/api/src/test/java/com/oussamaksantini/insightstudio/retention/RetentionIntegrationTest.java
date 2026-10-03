package com.oussamaksantini.insightstudio.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.oussamaksantini.insightstudio.PostgresIntegrationTest;
import com.oussamaksantini.insightstudio.SqlFixture;
import com.oussamaksantini.insightstudio.tenancy.Role;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts;
import com.oussamaksantini.insightstudio.testsupport.TestAccounts.TestUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The daily retention purge (docs/data-retention.md): audit events after 400 days, used or expired
 * tokens after 7 days, closed invitations after 400 days; open invitations and recent rows are kept.
 */
class RetentionIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RetentionJob retention;

    @Test
    void purgesOnlyWhatIsPastItsRetention() {
        SqlFixture db = new SqlFixture(jdbc);
        db.clear();
        long business = db.business("Old Co", "old-co", "EUR", "UTC");
        TestUser user = new TestAccounts(jdbc).member("old@old.co", business, Role.OWNER);

        jdbc.update("INSERT INTO audit_events (business_id, action, created_at) VALUES (?, 'business.renamed', now() - interval '401 days')",
                business);
        jdbc.update("INSERT INTO audit_events (business_id, action, created_at) VALUES (?, 'business.exported', now() - interval '399 days')",
                business);

        for (String table : List.of("password_reset_tokens", "email_verification_tokens")) {
            String insert = "INSERT INTO " + table + " (user_id, token_sha256, expires_at, used_at, created_at) VALUES (?, ?, %s, %s, now() - interval '30 days')";
            // Expired 8 days ago: purged. Used 8 days ago: purged. Used 6 days ago: kept. Unused and valid: kept.
            jdbc.update(insert.formatted("now() - interval '8 days'", "NULL"), user.id(), table.charAt(0) + "1".repeat(63));
            jdbc.update(insert.formatted("now() + interval '1 day'", "now() - interval '8 days'"), user.id(), table.charAt(0) + "2".repeat(63));
            jdbc.update(insert.formatted("now() + interval '1 day'", "now() - interval '6 days'"), user.id(), table.charAt(0) + "3".repeat(63));
            jdbc.update(insert.formatted("now() + interval '1 day'", "NULL"), user.id(), table.charAt(0) + "4".repeat(63));
        }

        String invitation = """
                INSERT INTO invitations (business_id, email, role, token_sha256, invited_by, created_at, expires_at, accepted_at, revoked_at)
                VALUES (?, ?, 'VIEWER', ?, ?, now() - interval '500 days', %s, %s, %s)
                """;
        // Closed for more than 400 days (accepted, revoked, expired): purged.
        jdbc.update(invitation.formatted("now() - interval '493 days'", "now() - interval '450 days'", "NULL"),
                business, "accepted@old.co", "1".repeat(64), user.id());
        jdbc.update(invitation.formatted("now() - interval '493 days'", "NULL", "now() - interval '401 days'"),
                business, "revoked@old.co", "2".repeat(64), user.id());
        jdbc.update(invitation.formatted("now() - interval '401 days'", "NULL", "NULL"),
                business, "expired@old.co", "3".repeat(64), user.id());
        // Closed recently: kept. Open (still valid): never purged.
        jdbc.update(invitation.formatted("now() - interval '10 days'", "NULL", "NULL"),
                business, "recent@old.co", "4".repeat(64), user.id());
        jdbc.update(invitation.formatted("now() + interval '1 day'", "NULL", "NULL"),
                business, "open@old.co", "5".repeat(64), user.id());

        Map<String, Integer> deleted = retention.purge();

        assertThat(deleted).containsEntry("auditEvents", 1).containsEntry("password_reset_tokens", 2)
                .containsEntry("email_verification_tokens", 2).containsEntry("invitations", 3);
        assertThat(jdbc.queryForList("SELECT action FROM audit_events", String.class)).containsExactly("business.exported");
        assertThat(jdbc.queryForList("SELECT email FROM invitations ORDER BY email", String.class))
                .containsExactly("open@old.co", "recent@old.co");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_tokens", Long.class)).isEqualTo(2);
        // Idempotent: nothing left to purge.
        assertThat(retention.purge().values()).allMatch(n -> n == 0);
    }
}
