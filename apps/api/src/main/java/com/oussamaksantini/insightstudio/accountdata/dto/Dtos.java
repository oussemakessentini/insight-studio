package com.oussamaksantini.insightstudio.accountdata.dto;

import com.oussamaksantini.insightstudio.billing.BillingDeletion.SubscriptionToCancel;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.util.List;
import java.util.Map;

/** Request and response bodies of the export and deletion endpoints (docs/account-management-contract.md §3, §4). */
public final class Dtos {

    private Dtos() {
    }

    /** {@code DELETE /api/businesses/{id}}: the caller's password and the business name typed again. */
    public record DeleteBusinessRequest(String password, String confirmName) {
    }

    /** {@code DELETE /api/account}: the caller's password and the account email typed again. */
    public record DeleteAccountRequest(String password, String confirmEmail) {
    }

    public record BusinessRef(long id, String name) {
    }

    public record OtherMember(long userId, String displayName, Role role) {
    }

    /**
     * {@code GET /api/businesses/{id}/deletion-preview}.
     *
     * @param subscription the paid subscription the deletion cancels ({@code plan}, {@code status},
     *     {@code message}), or {@code null} when there is none to cancel
     */
    public record BusinessDeletionPreview(BusinessRef business, Map<String, Long> counts, List<OtherMember> otherMembers,
            SubscriptionToCancel subscription) {
    }

    public record AccountRef(String email, String displayName) {
    }

    public record MembershipPreview(long businessId, String businessName, Role role, long memberCount) {
    }

    public record BlockingBusiness(long businessId, String businessName) {
    }

    /**
     * {@code GET /api/account/deletion-preview}.
     *
     * @param openInvitationsSent open invitations the account sent (in any business); deleting the account
     *     revokes them
     */
    public record AccountDeletionPreview(AccountRef account, List<MembershipPreview> memberships,
            List<BlockingBusiness> blockingBusinesses, Map<String, Long> authoredContent, long openInvitationsSent) {
    }
}
