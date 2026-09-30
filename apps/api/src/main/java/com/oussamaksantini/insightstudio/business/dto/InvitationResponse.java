package com.oussamaksantini.insightstudio.business.dto;

import com.oussamaksantini.insightstudio.tenancy.Role;
import java.time.Instant;

/**
 * An open invitation, as its business's owners and admins see it. It never says whether the
 * address has an account.
 */
public record InvitationResponse(
        long id, String email, Role role, String invitedBy, Instant createdAt, Instant expiresAt) {
}
