package com.oussamaksantini.insightstudio.audit;

import com.oussamaksantini.insightstudio.account.AccountPrincipal;
import com.oussamaksantini.insightstudio.audit.dto.AuditEventResponse;
import com.oussamaksantini.insightstudio.audit.dto.AuditPageResponse;
import com.oussamaksantini.insightstudio.business.MemberAccess;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The audit history of a business (ADMIN+; docs/account-management-contract.md §2). */
@Service
public class AuditService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    private final MemberAccess access;
    private final AuditQueries queries;

    AuditService(MemberAccess access, AuditQueries queries) {
        this.access = access;
        this.queries = queries;
    }

    /**
     * @param before only events with a smaller id (the previous page's {@code nextBefore})
     * @param category one of business, member, import, chart, dashboard; {@code null} for all
     */
    @Transactional(readOnly = true)
    public AuditPageResponse page(AccountPrincipal caller, long businessId, Integer limit, Long before, String category) {
        access.require(caller, businessId, Role.ADMIN);
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw ApiException.badRequest("'limit' must be between 1 and %d.".formatted(MAX_LIMIT));
        }
        if (before != null && before < 1) {
            throw ApiException.badRequest("'before' must be an event id.");
        }
        List<String> prefixes = null;
        if (category != null && !category.isBlank()) {
            AuditAction.Category parsed = AuditAction.Category.parse(category);
            if (parsed == null) {
                throw ApiException.badRequest("'category' must be one of business, member, import, chart, dashboard.");
            }
            prefixes = parsed.prefixes();
        }
        List<AuditEventResponse> events = queries.page(businessId, before, prefixes, size + 1);
        boolean more = events.size() > size;
        List<AuditEventResponse> shown = more ? events.subList(0, size) : events;
        return new AuditPageResponse(List.copyOf(shown), more ? shown.getLast().id() : null);
    }
}
