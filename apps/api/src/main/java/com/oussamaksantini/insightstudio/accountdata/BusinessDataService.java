package com.oussamaksantini.insightstudio.accountdata;

import com.oussamaksantini.insightstudio.account.AccountPrincipal;
import com.oussamaksantini.insightstudio.account.AccountService;
import com.oussamaksantini.insightstudio.accountdata.AccountDataQueries.BusinessRow;
import com.oussamaksantini.insightstudio.accountdata.dto.Dtos.BusinessDeletionPreview;
import com.oussamaksantini.insightstudio.accountdata.dto.Dtos.BusinessRef;
import com.oussamaksantini.insightstudio.accountdata.dto.Dtos.OtherMember;
import com.oussamaksantini.insightstudio.audit.AuditAction;
import com.oussamaksantini.insightstudio.audit.AuditLog;
import com.oussamaksantini.insightstudio.business.BusinessService;
import com.oussamaksantini.insightstudio.business.MemberAccess;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.security.RateLimit;
import com.oussamaksantini.insightstudio.security.RateLimiter;
import com.oussamaksantini.insightstudio.tenancy.Memberships;
import com.oussamaksantini.insightstudio.tenancy.PublicDemo;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Business export and deletion (docs/account-management-contract.md §3, §4). Both are OWNER-only
 * with a verified email address; a business the caller is not a member of is a 404, and the public
 * demo (no members) can never be exported or deleted.
 */
@Service
public class BusinessDataService {

    private static final Logger log = LoggerFactory.getLogger(BusinessDataService.class);

    static final String CONFIRM_NAME = "Type the business name exactly as shown to confirm.";
    static final String DEMO = "The public demo business can't be deleted.";

    private final MemberAccess access;
    private final PublicDemo demo;
    private final Memberships memberships;
    private final AccountDataQueries queries;
    private final BusinessExport export;
    private final AuditLog audit;
    private final AccountService accounts;
    private final RateLimiter limits;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate readOnly;

    BusinessDataService(MemberAccess access, Memberships memberships, AccountDataQueries queries, BusinessExport export,
            AuditLog audit, AccountService accounts, RateLimiter limits, Clock clock, PlatformTransactionManager manager,
            PublicDemo demo) {
        this.access = access;
        this.demo = demo;
        this.memberships = memberships;
        this.queries = queries;
        this.export = export;
        this.audit = audit;
        this.accounts = accounts;
        this.limits = limits;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
        // One snapshot for the whole export: every file shows the same moment.
        this.snapshot = new TransactionTemplate(manager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readOnly = new TransactionTemplate(manager);
        this.readOnly.setReadOnly(true);
    }

    /** A ZIP file and its name. */
    public record ExportFile(String fileName, byte[] content) {
    }

    /**
     * The business's data as a ZIP of CSV and JSON files ({@link BusinessExport}). Writes
     * {@code business.exported} in the same transaction, so the export's own audit file lists it.
     * 429 past {@link RateLimit#BUSINESS_EXPORTS} per business.
     */
    public ExportFile export(AccountPrincipal caller, long businessId) {
        access.requireVerified(caller, businessId, Role.OWNER);
        limits.acquire(RateLimit.BUSINESS_EXPORTS, "business:" + businessId);
        ExportFile file = snapshot.execute(status -> {
            BusinessRow business = queries.business(businessId, false).orElseThrow(BusinessDataService::notFound);
            audit.record(businessId, caller.userId(), AuditAction.BUSINESS_EXPORTED, businessId);
            byte[] zip = export.zip(business, clock.instant());
            String date = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
            return new ExportFile("insight-studio-%s-%s.zip".formatted(business.slug(), date), zip);
        });
        log.info("Account {} exported business {} ({} bytes).", caller.userId(), businessId, file.content().length);
        return file;
    }

    /** What deleting the business would remove, and who else loses access. */
    public BusinessDeletionPreview deletionPreview(AccountPrincipal caller, long businessId) {
        access.requireVerified(caller, businessId, Role.OWNER);
        return readOnly.execute(status -> {
            BusinessRow business = queries.business(businessId, false).orElseThrow(BusinessDataService::notFound);
            List<OtherMember> others = queries.members(businessId).stream()
                    .filter(m -> m.userId() != caller.userId())
                    .map(m -> new OtherMember(m.userId(), m.displayName(), m.role()))
                    .toList();
            return new BusinessDeletionPreview(new BusinessRef(business.id(), business.name()),
                    queries.businessCounts(businessId), others);
        });
    }

    /**
     * Deletes the business and everything in it, in one transaction after locking the business row:
     * every business-scoped table, then the business; its pending emails expire with their bodies
     * erased; a Cube purge is queued at the data version after the deletion. Needs the caller's
     * password (checked like a password change, same rate limits) and the business name typed again.
     */
    public void delete(AccountPrincipal caller, long businessId, String password, String confirmName, String clientIp) {
        access.requireVerified(caller, businessId, Role.OWNER);
        BusinessRow business = queries.business(businessId, false).orElseThrow(BusinessDataService::notFound);
        // The configured public demo business (by slug, whether or not the demo is on and even if an
        // operator gave it members) is never deleted through the API.
        if (business.slug().equalsIgnoreCase(demo.reservedSlug())) {
            throw ApiException.conflict(DEMO);
        }
        if (confirmName == null || !confirmName.strip().equals(business.name())) {
            throw ApiException.badRequest(CONFIRM_NAME);
        }
        accounts.reauthenticate(caller, password, clientIp);
        Map<String, Integer> deleted = transactions.execute(status -> {
            BusinessRow locked = queries.business(businessId, true).orElseThrow(BusinessDataService::notFound);
            // The caller may have been demoted or removed since the checks above.
            if (memberships.role(caller.userId(), businessId).filter(r -> r == Role.OWNER).isEmpty()) {
                throw notFound();
            }
            Map<String, Integer> rows = queries.deleteBusiness(businessId);
            rows.put("mail_outbox (expired)", queries.expireBusinessMail(businessId));
            long purge = queries.insertCubePurge(businessId, locked.timeZone());
            rows.put("cube_purge_requests", (int) purge);
            return rows;
        });
        log.info("Account {} deleted business {}: {}", caller.userId(), businessId, deleted);
    }

    private static ApiException notFound() {
        return ApiException.notFound(BusinessService.NOT_FOUND);
    }
}
