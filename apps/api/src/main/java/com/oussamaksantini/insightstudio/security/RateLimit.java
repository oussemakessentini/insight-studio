package com.oussamaksantini.insightstudio.security;

import java.time.Duration;

/**
 * A limit of {@code max} counted events per subject within a sliding {@code window}
 * (docs/auth.md, "Rate limits").
 *
 * @param name part of the stored bucket key; unique per limit
 */
public record RateLimit(String name, int max, Duration window) {

    /** Failed sign-ins (and failed password changes) for one email. */
    public static final RateLimit SIGN_IN_PER_EMAIL = new RateLimit("sign-in:email", 5, Duration.ofMinutes(15));
    /** Failed sign-ins (and failed password changes) from one client IP. */
    public static final RateLimit SIGN_IN_PER_IP = new RateLimit("sign-in:ip", 20, Duration.ofMinutes(15));
    /** Sign-up attempts from one client IP, successful or not. */
    public static final RateLimit SIGN_UP_PER_IP = new RateLimit("sign-up:ip", 10, Duration.ofHours(1));
    /** Password reset requests from one client IP. */
    public static final RateLimit RESET_REQUEST_PER_IP = new RateLimit("reset-request:ip", 10, Duration.ofHours(1));
    /** Reset emails to one address; further requests are accepted but send nothing. */
    public static final RateLimit RESET_EMAIL_PER_ADDRESS = new RateLimit("reset-request:email", 3, Duration.ofHours(1));
    /** Attempts to use a reset token from one client IP. */
    public static final RateLimit RESET_CONFIRM_PER_IP = new RateLimit("reset-confirm:ip", 20, Duration.ofMinutes(15));

    public RateLimit {
        if (max < 1 || window.isNegative() || window.isZero() || window.compareTo(RateLimiter.RETENTION) > 0) {
            throw new IllegalArgumentException("Invalid rate limit " + name);
        }
    }
}
