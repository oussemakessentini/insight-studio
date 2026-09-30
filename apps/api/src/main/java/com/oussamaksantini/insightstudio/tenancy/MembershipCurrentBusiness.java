package com.oussamaksantini.insightstudio.tenancy;

import com.oussamaksantini.insightstudio.account.AccountPrincipal;
import com.oussamaksantini.insightstudio.account.CurrentAccount;
import com.oussamaksantini.insightstudio.account.EmailVerificationService;
import com.oussamaksantini.insightstudio.account.UserQueries;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.tenancy.Memberships.MembershipView;
import com.oussamaksantini.insightstudio.tenancy.PublicDemo.DemoBusiness;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the one business a request acts on (docs/accounts-contract.md §3).
 *
 * <ol>
 *   <li>Signed in: {@value #HEADER} selects among the user's memberships. It is only a selector: the
 *       membership for (user, id) is loaded, and a business the user does not belong to is a 404, so
 *       other businesses' ids are never confirmed. Without the header: the only membership, a 400
 *       when there are several, a 404 when there are none.</li>
 *   <li>Anonymous with the public demo enabled: the demo business, read-only; a header naming any
 *       other business is a 401.</li>
 *   <li>Anonymous otherwise: 401.</li>
 * </ol>
 *
 * The result is cached for the rest of the request.
 */
@Component
class MembershipCurrentBusiness implements CurrentBusiness {

    static final String HEADER = "X-Business-Id";
    static final String SIGN_IN = "Sign in to continue.";
    static final String NOT_FOUND = "Business not found.";
    private static final String CACHE = MembershipCurrentBusiness.class.getName() + ".ACCESS";

    private final Memberships memberships;
    private final PublicDemo demo;
    private final UserQueries users;

    MembershipCurrentBusiness(Memberships memberships, PublicDemo demo, UserQueries users) {
        this.memberships = memberships;
        this.demo = demo;
        this.users = users;
    }

    @Override
    public BusinessAccess require() {
        HttpServletRequest request = currentRequest();
        if (request.getAttribute(CACHE) instanceof BusinessAccess cached) {
            return cached;
        }
        String header = request.getHeader(HEADER);
        BusinessAccess access = CurrentAccount.get()
                .map(principal -> forUser(principal, header))
                .orElseGet(() -> forAnonymous(header));
        request.setAttribute(CACHE, access);
        return access;
    }

    @Override
    public BusinessAccess require(Role minimum) {
        BusinessAccess access = require();
        if (access.demo()) {
            throw ApiException.forbidden("The demo is read-only.");
        }
        if (!access.role().atLeast(minimum)) {
            throw ApiException.forbidden("You need the %s role for this.".formatted(minimum));
        }
        if (!access.emailVerified()) {
            throw ApiException.forbidden(EmailVerificationService.VERIFY_FIRST);
        }
        return access;
    }

    private BusinessAccess forUser(AccountPrincipal principal, String header) {
        long userId = principal.userId();
        if (header != null) {
            long businessId = parseId(header).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
            Role role = memberships.role(userId, businessId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
            return new BusinessAccess(businessId, role, false, userId, users.isEmailVerified(userId));
        }
        List<MembershipView> all = memberships.forUser(userId);
        if (all.isEmpty()) {
            throw ApiException.notFound(NOT_FOUND);
        }
        if (all.size() > 1) {
            throw ApiException.badRequest("Select a business (X-Business-Id).");
        }
        MembershipView only = all.getFirst();
        return new BusinessAccess(only.businessId(), only.role(), false, userId, users.isEmailVerified(userId));
    }

    private BusinessAccess forAnonymous(String header) {
        DemoBusiness business = demo.find().orElseThrow(() -> ApiException.unauthorized(SIGN_IN));
        if (header != null && parseId(header).filter(id -> id == business.businessId()).isEmpty()) {
            throw ApiException.unauthorized(SIGN_IN);
        }
        return new BusinessAccess(business.businessId(), Role.VIEWER, true, null, true);
    }

    private static Optional<Long> parseId(String value) {
        try {
            long id = Long.parseLong(value.strip());
            return id > 0 ? Optional.of(id) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return servlet.getRequest();
        }
        // Business data is only ever resolved for an HTTP request.
        throw ApiException.unauthorized(SIGN_IN);
    }
}
