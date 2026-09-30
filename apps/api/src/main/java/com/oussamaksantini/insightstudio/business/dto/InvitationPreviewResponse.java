package com.oussamaksantini.insightstudio.business.dto;

import com.oussamaksantini.insightstudio.tenancy.Role;
import java.time.Instant;

/**
 * What an invitation link offers, shown to whoever holds the link before they accept.
 *
 * @param email the address the invitation was sent to: only the account with this email can accept
 */
public record InvitationPreviewResponse(
        String businessName, Role role, String invitedBy, String email, Instant expiresAt) {
}
