package com.oussamaksantini.insightstudio.testsupport;

import com.oussamaksantini.insightstudio.account.PasswordResetNotifier;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records reset links instead of emailing them, so tests can follow them. */
public class CapturingPasswordResetNotifier implements PasswordResetNotifier {

    public record Sent(String email, String link) {

        public String token() {
            return link.substring(link.indexOf("token=") + "token=".length());
        }
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public void sendResetLink(String email, String displayName, String resetLink, java.time.Instant expiresAt) {
        sent.add(new Sent(email, resetLink));
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public Optional<Sent> last() {
        return sent.isEmpty() ? Optional.empty() : Optional.of(sent.getLast());
    }

    public void clear() {
        sent.clear();
    }
}
