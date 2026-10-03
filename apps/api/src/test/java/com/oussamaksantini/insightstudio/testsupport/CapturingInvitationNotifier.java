package com.oussamaksantini.insightstudio.testsupport;

import com.oussamaksantini.insightstudio.business.InvitationNotifier;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records invitation links instead of emailing them, so tests can follow them. */
public class CapturingInvitationNotifier implements InvitationNotifier {

    public record Sent(String email, String invitedBy, String businessName, Role role, String link, Instant expiresAt) {

        public String token() {
            return link.substring(link.indexOf("token=") + "token=".length());
        }
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public void sendInvitation(long businessId, String email, String invitedBy, String businessName, Role role, String link, Instant expiresAt) {
        sent.add(new Sent(email, invitedBy, businessName, role, link, expiresAt));
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public Sent last() {
        return Optional.ofNullable(sent.isEmpty() ? null : sent.getLast()).orElseThrow();
    }

    public void clear() {
        sent.clear();
    }
}
