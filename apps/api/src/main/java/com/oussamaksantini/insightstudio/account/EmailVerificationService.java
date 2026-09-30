package com.oussamaksantini.insightstudio.account;

import com.oussamaksantini.insightstudio.account.UserQueries.UserRow;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.security.RateLimit;
import com.oussamaksantini.insightstudio.security.RateLimiter;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Email verification (docs/auth.md, "Email verification"). Links carry a 256-bit token (only its
 * SHA-256 is stored), work once, expire after {@link #TOKEN_LIFETIME}, and a new link invalidates
 * older ones. Until an account is verified it may sign in and read, but not create or change a
 * business ({@link #requireVerified}).
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    static final Duration TOKEN_LIFETIME = Duration.ofHours(24);
    static final String INVALID_LINK = "This verification link is invalid or has expired.";
    public static final String VERIFY_FIRST = "Verify your email address first: open the link we emailed you, or send a new one.";
    private static final int TOKEN_BYTES = 32;

    private final UserQueries users;
    private final VerificationNotifier notifier;
    private final AccountProperties links;
    private final RateLimiter limits;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    EmailVerificationService(
            UserQueries users,
            VerificationNotifier notifier,
            AccountProperties links,
            RateLimiter limits,
            TransactionTemplate transactions) {
        this.users = users;
        this.notifier = notifier;
        this.links = links;
        this.limits = limits;
        this.transactions = transactions;
    }

    /**
     * Issues a new link for {@code user} (older links stop working) and queues its email. Must run
     * inside the caller's transaction, so the token and its email are stored together.
     */
    void issue(UserRow user) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Verification links are issued inside a transaction.");
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(TOKEN_LIFETIME);
        users.invalidateVerificationTokens(user.id());
        users.insertVerificationToken(user.id(), AccountService.sha256(token), expiresAt);
        notifier.sendVerificationLink(user.email(), user.displayName(), links.link("/verify-email", token), expiresAt);
    }

    /** Emails the owner of an existing account that someone tried to sign up with its address. */
    void noticeExistingAccount(UserRow user) {
        notifier.sendExistingAccountNotice(user.email(), user.displayName(), links.page("/sign-in"), links.page("/forgot-password"));
    }

    /**
     * Verifies the address the token was sent to. Public: the link may be opened in any browser,
     * signed in as anyone (or no one); only the token's own account is verified. 400 for an unknown,
     * used or expired token.
     */
    public void verify(String token, String clientIp) {
        limits.acquire(RateLimit.VERIFICATION_TOKEN_PER_IP, clientIp);
        if (token == null || token.isBlank() || token.length() > 100) {
            throw ApiException.badRequest(INVALID_LINK);
        }
        long userId = transactions.execute(status -> {
            long owner = users.consumeVerificationToken(AccountService.sha256(token.strip()))
                    .orElseThrow(() -> ApiException.badRequest(INVALID_LINK));
            users.markEmailVerified(owner);
            users.invalidateVerificationTokens(owner);
            return owner;
        });
        log.info("Email address of account {} verified.", userId);
    }

    /**
     * Sends a new link to the signed-in account's address. Always 202-style (nothing to answer):
     * already verified accounts get nothing. 429 past {@link RateLimit#VERIFICATION_EMAILS_PER_ACCOUNT}.
     */
    public void resend(AccountPrincipal caller) {
        UserRow user = users.findById(caller.userId()).orElseThrow(() -> ApiException.unauthorized("Sign in to continue."));
        if (user.emailVerified()) {
            return;
        }
        limits.acquire(RateLimit.VERIFICATION_EMAILS_PER_ACCOUNT, caller.getName());
        transactions.executeWithoutResult(status -> issue(user));
    }

    /**
     * Marks the account's address verified because it proved control of the mailbox another way
     * (an invitation or a reset link sent to it). Runs in the caller's transaction.
     */
    public void verifiedByEmailLink(long userId) {
        if (users.markEmailVerified(userId)) {
            users.invalidateVerificationTokens(userId);
            log.info("Email address of account {} verified by an emailed link.", userId);
        }
    }

    /** @throws ApiException 403 unless the account's email address is verified */
    public void requireVerified(long userId) {
        if (!users.isEmailVerified(userId)) {
            throw ApiException.forbidden(VERIFY_FIRST);
        }
    }
}
