package com.oussamaksantini.insightstudio.business;

import com.oussamaksantini.insightstudio.account.AccountPrincipal;
import com.oussamaksantini.insightstudio.account.CurrentAccount;
import com.oussamaksantini.insightstudio.account.EmailVerificationService;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.tenancy.Memberships;
import com.oussamaksantini.insightstudio.tenancy.Role;
import org.springframework.stereotype.Component;

/**
 * Access checks for endpoints that name their business in the path ({@code /api/businesses/{id}/...}).
 * The path is only a selector: the caller's membership is loaded, and a business the caller does not
 * belong to is a 404 (the public demo has no members, so it is a 404 too; anonymous callers never get
 * this far: 401).
 */
@Component
public class MemberAccess {

    private final Memberships memberships;
    private final EmailVerificationService verification;

    MemberAccess(Memberships memberships, EmailVerificationService verification) {
        this.memberships = memberships;
        this.verification = verification;
    }

    /** The signed-in account of the request (the security rules already require one: 401 otherwise). */
    public static AccountPrincipal caller() {
        return CurrentAccount.get().orElseThrow(() -> ApiException.unauthorized("Sign in to continue."));
    }

    /** The caller's role: 404 when not a member, 403 when weaker than {@code minimum}. */
    public Role require(AccountPrincipal caller, long businessId, Role minimum) {
        Role role = memberships.role(caller.userId(), businessId)
                .orElseThrow(() -> ApiException.notFound(BusinessService.NOT_FOUND));
        if (!role.atLeast(minimum)) {
            throw ApiException.forbidden("You need the %s role for this.".formatted(minimum));
        }
        return role;
    }

    /** Like {@link #require}, and the caller's email address must be verified (403 otherwise). */
    public Role requireVerified(AccountPrincipal caller, long businessId, Role minimum) {
        Role role = require(caller, businessId, minimum);
        verification.requireVerified(caller.userId());
        return role;
    }
}
