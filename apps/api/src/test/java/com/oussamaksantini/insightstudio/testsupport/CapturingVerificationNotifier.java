package com.oussamaksantini.insightstudio.testsupport;

import com.oussamaksantini.insightstudio.account.VerificationNotifier;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records verification links instead of emailing them, so tests can follow them. */
public class CapturingVerificationNotifier implements VerificationNotifier {

    public record Sent(String email, String link, Instant expiresAt) {

        public String token() {
            return link.substring(link.indexOf("token=") + "token=".length());
        }
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final List<String> notices = new CopyOnWriteArrayList<>();

    @Override
    public void sendVerificationLink(long userId, String email, String displayName, String link, Instant expiresAt) {
        sent.add(new Sent(email, link, expiresAt));
    }

    @Override
    public void sendExistingAccountNotice(long userId, String email, String displayName, String signInLink, String resetLink) {
        notices.add(email);
    }

    /** Addresses told "you already have an account". */
    public List<String> notices() {
        return List.copyOf(notices);
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public List<Sent> sentTo(String email) {
        return sent.stream().filter(s -> s.email().equalsIgnoreCase(email)).toList();
    }

    public Sent last() {
        return sent.getLast();
    }

    public void clear() {
        sent.clear();
        notices.clear();
    }
}
