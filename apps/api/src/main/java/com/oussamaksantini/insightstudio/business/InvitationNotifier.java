package com.oussamaksantini.insightstudio.business;

import com.oussamaksantini.insightstudio.tenancy.Role;
import java.time.Instant;

/**
 * Delivers an invitation link. The link carries a secret, single-use token: implementations must
 * send it only to the invited address and must not log or store it elsewhere.
 */
public interface InvitationNotifier {

    void sendInvitation(String email, String invitedBy, String businessName, Role role, String link, Instant expiresAt);
}
