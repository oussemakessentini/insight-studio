package com.oussamaksantini.insightstudio.retention;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily retention purge (docs/data-retention.md, docs/account-management-api.md). Every instance
 * may run it: each statement is idempotent.
 *
 * <ul>
 *   <li>audit events older than {@code insight.retention.audit-days} (400);</li>
 *   <li>password-reset and email-verification tokens {@code insight.retention.token-days} (7) after
 *       they were used or expired;</li>
 *   <li>invitations closed (accepted, revoked or expired) for more than
 *       {@code insight.retention.invitation-days} (400); open invitations are never purged;</li>
 *   <li>billing webhook events finished (processed or ignored), subscription cancellations done and
 *       provider operations finished more than {@code insight.retention.billing-days} (30) ago; pending
 *       ones are never purged (checkout and customer calls pending over a day are abandoned first:
 *       their idempotency key can no longer be reused). Expired subscription leases.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(RetentionJob.RetentionProperties.class)
@ConditionalOnProperty(name = "insight.retention.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    /**
     * {@code insight.retention.*}.
     *
     * @param enabled whether this instance runs the purge
     * @param cron when it runs (Spring cron, server time zone), daily by default
     */
    @ConfigurationProperties("insight.retention")
    public record RetentionProperties(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0 17 3 * * *") String cron,
            @DefaultValue("400") int auditDays,
            @DefaultValue("7") int tokenDays,
            @DefaultValue("400") int invitationDays,
            @DefaultValue("30") int billingDays) {

        public RetentionProperties {
            if (auditDays < 1 || tokenDays < 1 || invitationDays < 1 || billingDays < 1) {
                throw new IllegalStateException("insight.retention.*-days must be at least 1");
            }
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RetentionProperties properties;

    RetentionJob(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
    }

    @Scheduled(cron = "${insight.retention.cron:0 17 3 * * *}")
    void scheduled() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.warn("Retention purge failed (retried tomorrow): {}", e.getClass().getSimpleName());
        }
    }

    /** Runs every purge now; returns the rows deleted per kind. */
    public Map<String, Integer> purge() {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        Map<String, Object> audit = Map.of("days", properties.auditDays());
        Map<String, Object> tokens = Map.of("days", properties.tokenDays());
        Map<String, Object> invitations = Map.of("days", properties.invitationDays());
        Map<String, Object> billing = Map.of("days", properties.billingDays());
        transactions.executeWithoutResult(status -> {
            deleted.put("auditEvents", jdbc.update(
                    "DELETE FROM audit_events WHERE created_at < now() - make_interval(days => :days)", audit));
            for (String table : new String[] {"password_reset_tokens", "email_verification_tokens"}) {
                deleted.put(table, jdbc.update("DELETE FROM " + table + """
                         WHERE expires_at < now() - make_interval(days => :days)
                            OR used_at < now() - make_interval(days => :days)
                        """, tokens));
            }
            deleted.put("invitations", jdbc.update("""
                    DELETE FROM invitations
                    WHERE (accepted_at IS NOT NULL OR revoked_at IS NOT NULL OR expires_at <= now())
                      AND LEAST(COALESCE(accepted_at, 'infinity'), COALESCE(revoked_at, 'infinity'), expires_at)
                          < now() - make_interval(days => :days)
                    """, invitations));
            deleted.put("billingEvents", jdbc.update("""
                    DELETE FROM billing_events
                    WHERE status <> 'PENDING' AND processed_at < now() - make_interval(days => :days)
                    """, billing));
            deleted.put("billingCancellations", jdbc.update("""
                    DELETE FROM billing_cancellations
                    WHERE status = 'DONE' AND finished_at < now() - make_interval(days => :days)
                    """, billing));
            // Provider calls whose key can no longer be reused (Stripe keeps keys 24 hours) are abandoned
            // first, so they are purged like finished ones; pending expiries are never touched.
            jdbc.update("""
                    UPDATE billing_operations
                    SET status = 'ABANDONED', finished_at = now(), updated_at = now(),
                        last_error = COALESCE(last_error, 'Idempotency key too old to reuse')
                    WHERE status = 'PENDING' AND kind IN ('create_customer', 'create_checkout')
                      AND created_at < now() - interval '1 day'
                    """, Map.of());
            deleted.put("billingOperations", jdbc.update("""
                    DELETE FROM billing_operations
                    WHERE status <> 'PENDING' AND finished_at < now() - make_interval(days => :days)
                    """, billing));
            deleted.put("billingLeases", jdbc.update(
                    "DELETE FROM billing_subscription_leases WHERE locked_until < now() - interval '1 hour'", Map.of()));
        });
        log.info("Retention purge: {}", deleted);
        return deleted;
    }
}
