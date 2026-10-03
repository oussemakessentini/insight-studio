package com.oussamaksantini.insightstudio.accountdata;

import com.oussamaksantini.insightstudio.account.AccountPrincipal;
import com.oussamaksantini.insightstudio.account.AccountService;
import com.oussamaksantini.insightstudio.account.UserQueries.UserRow;
import com.oussamaksantini.insightstudio.accountdata.AccountDataQueries.AccountRow;
import com.oussamaksantini.insightstudio.accountdata.AccountDataQueries.MembershipRow;
import com.oussamaksantini.insightstudio.accountdata.dto.Dtos.AccountDeletionPreview;
import com.oussamaksantini.insightstudio.accountdata.dto.Dtos.AccountRef;
import com.oussamaksantini.insightstudio.accountdata.dto.Dtos.BlockingBusiness;
import com.oussamaksantini.insightstudio.accountdata.dto.Dtos.MembershipPreview;
import com.oussamaksantini.insightstudio.audit.AuditAction;
import com.oussamaksantini.insightstudio.audit.AuditLog;
import com.oussamaksantini.insightstudio.audit.AuditQueries;
import com.oussamaksantini.insightstudio.audit.dto.AuditEventResponse;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.security.RateLimit;
import com.oussamaksantini.insightstudio.security.RateLimiter;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The signed-in account's own data: export and deletion (docs/account-management-contract.md §3, §4).
 */
@Service
public class AccountDataService {

    private static final Logger log = LoggerFactory.getLogger(AccountDataService.class);
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    static final String CONFIRM_EMAIL = "Type your email address to confirm.";

    private final AccountDataQueries queries;
    private final AuditQueries auditEvents;
    private final AuditLog audit;
    private final AccountService accounts;
    private final RateLimiter limits;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;

    AccountDataService(AccountDataQueries queries, AuditQueries auditEvents, AuditLog audit, AccountService accounts,
            RateLimiter limits, Clock clock, PlatformTransactionManager manager) {
        this.queries = queries;
        this.auditEvents = auditEvents;
        this.audit = audit;
        this.accounts = accounts;
        this.limits = limits;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
        this.readOnly = new TransactionTemplate(manager);
        this.readOnly.setReadOnly(true);
    }

    /** A JSON file and its name. */
    public record ExportFile(String fileName, byte[] content) {
    }

    /**
     * Everything stored about the account: its profile, memberships, what it authored and the audit
     * events it performed. Never its password hash, sessions or tokens, and nothing about other people
     * beyond business names (the email of a person it invited is left out of those events' details).
     * 429 past {@link RateLimit#ACCOUNT_EXPORTS}.
     */
    public ExportFile export(AccountPrincipal caller) {
        limits.acquire(RateLimit.ACCOUNT_EXPORTS, caller.getName());
        Instant now = clock.instant();
        Map<String, Object> document = readOnly.execute(status -> {
            AccountRow account = queries.account(caller.userId())
                    .orElseThrow(() -> ApiException.unauthorized("Sign in to continue."));
            long u = account.id();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("exportedAt", now.toString());
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("id", account.id());
            profile.put("email", account.email());
            profile.put("displayName", account.displayName());
            profile.put("createdAt", iso(account.createdAt()));
            profile.put("emailVerifiedAt", isoOrNull(account.emailVerifiedAt()));
            profile.put("lastSignInAt", isoOrNull(account.lastSignInAt()));
            out.put("account", profile);
            List<Map<String, Object>> memberships = new ArrayList<>();
            for (MembershipRow m : queries.memberships(u)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("businessId", m.businessId());
                row.put("businessName", m.businessName());
                row.put("role", m.role().name());
                row.put("since", iso(m.since()));
                memberships.add(row);
            }
            out.put("memberships", memberships);
            Map<String, Object> authored = new LinkedHashMap<>();
            authored.put("charts", rows(queries.authored("""
                    SELECT business_id AS "businessId", id, title FROM chart_definitions WHERE created_by = :u ORDER BY id
                    """, u)));
            authored.put("dashboards", rows(queries.authored("""
                    SELECT business_id AS "businessId", id, name FROM dashboards WHERE created_by = :u ORDER BY id
                    """, u)));
            authored.put("savedReports", rows(queries.authored("""
                    SELECT business_id AS "businessId", id, name, kind FROM saved_reports WHERE created_by = :u ORDER BY id
                    """, u)));
            authored.put("imports", rows(queries.authored("""
                    SELECT business_id AS "businessId", id, kind, file_name AS "fileName", created_at AS "createdAt"
                    FROM import_batches WHERE created_by = :u ORDER BY id
                    """, u)));
            out.put("authored", authored);
            List<Map<String, Object>> events = new ArrayList<>();
            for (AuditQueries.Row row : auditEvents.byActor(u)) {
                AuditEventResponse e = row.event();
                ObjectNode details = (ObjectNode) e.details().deepCopy();
                // Another person's address is theirs, not part of this account's data.
                details.remove("email");
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("id", e.id());
                event.put("businessId", row.businessId());
                event.put("action", e.action());
                event.put("targetType", e.targetType());
                event.put("targetId", e.targetId());
                event.put("details", details);
                event.put("createdAt", e.createdAt().toString());
                events.add(event);
            }
            out.put("auditEvents", events);
            return out;
        });
        String date = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        log.info("Account {} exported its data.", caller.userId());
        return new ExportFile("insight-studio-account-%s.json".formatted(date), JSON.writeValueAsBytes(document));
    }

    public AccountDeletionPreview deletionPreview(AccountPrincipal caller) {
        return readOnly.execute(status -> {
            AccountRow account = queries.account(caller.userId())
                    .orElseThrow(() -> ApiException.unauthorized("Sign in to continue."));
            List<MembershipRow> memberships = queries.memberships(account.id());
            return new AccountDeletionPreview(
                    new AccountRef(account.email(), account.displayName()),
                    memberships.stream().map(m -> new MembershipPreview(m.businessId(), m.businessName(), m.role(),
                            m.memberCount())).toList(),
                    blocking(memberships),
                    queries.authoredCounts(account.id()),
                    queries.countOpenInvitationsSent(account.id()));
        });
    }

    /**
     * Deletes the account (contract §4), in one transaction: {@code member.account_deleted} in each of
     * its businesses and the memberships deleted; the open invitations it sent revoked (with
     * {@code invitation.revoked} and their pending emails expired); open invitations sent to its email revoked; its reset
     * and verification tokens deleted; its pending emails expired with their bodies erased; every
     * session deleted through the principal index and the session version bumped; the user row turned
     * into a tombstone. 409 while it is the only owner of a business. Needs the password and the email
     * typed again. The caller clears the request's own session cookie.
     */
    public void delete(AccountPrincipal caller, String password, String confirmEmail, String clientIp) {
        AccountRow account = queries.account(caller.userId())
                .orElseThrow(() -> ApiException.unauthorized("Sign in to continue."));
        if (confirmEmail == null || !confirmEmail.strip().equalsIgnoreCase(account.email())) {
            throw ApiException.badRequest(CONFIRM_EMAIL);
        }
        UserRow user = accounts.reauthenticate(caller, password, clientIp);
        transactions.executeWithoutResult(status -> {
            queries.lockUser(user.id());
            queries.lockMembershipBusinesses(user.id());
            List<MembershipRow> memberships = queries.memberships(user.id());
            List<BlockingBusiness> blocking = blocking(memberships);
            if (!blocking.isEmpty()) {
                throw ApiException.conflict(lastOwner(blocking));
            }
            for (MembershipRow m : memberships) {
                audit.record(m.businessId(), user.id(), AuditAction.MEMBER_ACCOUNT_DELETED, user.id(),
                        Map.of("role", m.role().name()));
            }
            // Invitations it sent would no longer work (their inviter is gone): revoke them, with their
            // events written while the account still exists, and cancel their emails.
            for (AccountDataQueries.RevokedInvitation invitation : queries.revokeInvitationsSentBy(user.id())) {
                audit.record(invitation.businessId(), user.id(), AuditAction.INVITATION_REVOKED, invitation.id(),
                        Map.of("email", invitation.email(), "role", invitation.role().name()));
                queries.expireInvitationMail(invitation.businessId(), invitation.email());
            }
            queries.deleteMemberships(user.id());
            queries.revokeInvitationsTo(user.email());
            queries.deleteAccountTokens(user.id());
            queries.expireAccountMail(user.id(), user.email());
            queries.deleteSessions(caller.getName());
            queries.tombstone(user.id(), AuditQueries.DELETED_ACCOUNT);
        });
        log.info("Account {} deleted (tombstone kept); all its sessions ended.", user.id());
    }

    private static List<BlockingBusiness> blocking(List<MembershipRow> memberships) {
        return memberships.stream()
                .filter(m -> m.role() == Role.OWNER && m.ownerCount() <= 1)
                .map(m -> new BlockingBusiness(m.businessId(), m.businessName()))
                .toList();
    }

    static String lastOwner(List<BlockingBusiness> blocking) {
        String names = blocking.stream().map(BlockingBusiness::businessName).collect(Collectors.joining(", "));
        return "You are the only owner of %s. Make another member an owner or delete the business first.".formatted(names);
    }

    private static List<Map<String, Object>> rows(List<Map<String, Object>> rows) {
        rows.forEach(row -> row.replaceAll((key, value) -> value instanceof OffsetDateTime time ? iso(time)
                : value instanceof java.sql.Timestamp ts ? ts.toInstant().toString() : value));
        return rows;
    }

    private static String iso(OffsetDateTime time) {
        return time.toInstant().toString();
    }

    private static String isoOrNull(OffsetDateTime time) {
        return time == null ? null : iso(time);
    }
}
