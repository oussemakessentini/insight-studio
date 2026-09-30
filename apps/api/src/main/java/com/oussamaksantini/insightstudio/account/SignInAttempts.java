package com.oussamaksantini.insightstudio.account;

import com.oussamaksantini.insightstudio.common.web.TooManyRequestsException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * In-memory brute-force protection for sign-in (and password change): at most
 * {@value #MAX_PER_EMAIL} failures per email and {@value #MAX_PER_IP} per client IP within
 * {@link #WINDOW}; further attempts get a 429 with {@code Retry-After} until the oldest counted
 * failure leaves the window. Per instance and lost on restart (see docs/auth.md).
 */
@Component
public class SignInAttempts {

    static final int MAX_PER_EMAIL = 5;
    static final int MAX_PER_IP = 20;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int CLEANUP_EVERY = 1_000;

    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final Clock clock;

    SignInAttempts() {
        this(Clock.systemUTC());
    }

    SignInAttempts(Clock clock) {
        this.clock = clock;
    }

    /** @throws TooManyRequestsException when the email or the IP has too many recent failures */
    void checkAllowed(String email, String ip) {
        long now = clock.millis();
        long wait = Math.max(waitMillis(emailKey(email), MAX_PER_EMAIL, now), waitMillis(ipKey(ip), MAX_PER_IP, now));
        if (wait > 0) {
            throw new TooManyRequestsException("Too many failed attempts. Try again later.", (wait + 999) / 1000);
        }
    }

    void recordFailure(String email, String ip) {
        long now = clock.millis();
        record(emailKey(email), MAX_PER_EMAIL, now);
        record(ipKey(ip), MAX_PER_IP, now);
        if (calls.incrementAndGet() % CLEANUP_EVERY == 0) {
            cleanup(now);
        }
    }

    /** A successful sign-in clears the email's failures (the IP's stay counted). */
    void recordSuccess(String email) {
        failures.remove(emailKey(email));
    }

    /** Forgets every failure. For tests. */
    public void reset() {
        failures.clear();
    }

    private long waitMillis(String key, int limit, long now) {
        Deque<Long> times = failures.get(key);
        if (times == null) {
            return 0;
        }
        synchronized (times) {
            prune(times, now);
            if (times.size() < limit) {
                return 0;
            }
            return times.peekFirst() + WINDOW.toMillis() - now;
        }
    }

    private void record(String key, int limit, long now) {
        Deque<Long> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            prune(times, now);
            times.addLast(now);
            while (times.size() > limit) {
                times.removeFirst();
            }
        }
    }

    private static void prune(Deque<Long> times, long now) {
        long oldest = now - WINDOW.toMillis();
        while (!times.isEmpty() && times.peekFirst() <= oldest) {
            times.removeFirst();
        }
    }

    private void cleanup(long now) {
        failures.entrySet().removeIf(entry -> {
            Deque<Long> times = entry.getValue();
            synchronized (times) {
                prune(times, now);
                return times.isEmpty();
            }
        });
    }

    private static String emailKey(String email) {
        return "email:" + (email == null ? "" : email.strip().toLowerCase(Locale.ROOT));
    }

    private static String ipKey(String ip) {
        return "ip:" + (ip == null ? "unknown" : ip);
    }
}
