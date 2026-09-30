package com.oussamaksantini.insightstudio.account;

import com.oussamaksantini.insightstudio.account.UserQueries.UserRow;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.security.RateLimit;
import com.oussamaksantini.insightstudio.security.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accounts: sign-up, password verification, password change and recovery
 * (docs/accounts-contract.md §1). Session handling lives in {@code SessionAuthentication}.
 *
 * <p>Rate limits ({@link RateLimiter}) are checked before any transaction starts and recorded in
 * their own, so a counted failure survives the rollback of the request that failed and a request
 * never holds two pooled connections. Writes that must happen together use {@code transactions}.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    static final String INVALID_CREDENTIALS = "Invalid email or password.";
    static final String INVALID_RESET_LINK = "This reset link is invalid or has expired.";
    static final Duration RESET_TOKEN_LIFETIME = Duration.ofMinutes(30);
    private static final int RESET_TOKEN_BYTES = 32;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_DISPLAY_NAME_LENGTH = 100;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final UserQueries users;
    private final PasswordEncoder encoder;
    private final RateLimiter limits;
    private final TransactionTemplate transactions;
    private final PasswordResetNotifier notifier;
    private final AccountProperties properties;
    private final SecureRandom random = new SecureRandom();
    /** Verified against for unknown emails, so they take as long as a wrong password. */
    private final String dummyHash;

    AccountService(
            UserQueries users,
            PasswordEncoder encoder,
            RateLimiter limits,
            TransactionTemplate transactions,
            PasswordResetNotifier notifier,
            AccountProperties properties) {
        this.users = users;
        this.encoder = encoder;
        this.limits = limits;
        this.transactions = transactions;
        this.notifier = notifier;
        this.properties = properties;
        this.dummyHash = encoder.encode("not-a-real-password-" + random.nextLong());
    }

    /**
     * Creates an account. 400 for invalid input, 409 when the email already has an account, 429
     * after too many attempts from the client IP (every attempt counts, so the 409 cannot be used
     * to test many emails for an account).
     */
    public UserRow signUp(String email, String password, String displayName, String clientIp) {
        limits.acquire(RateLimit.SIGN_UP_PER_IP, clientIp);
        String cleanEmail = checkEmail(email);
        String cleanName = checkDisplayName(displayName);
        PasswordPolicy.check(password, cleanEmail);
        if (users.findByEmail(cleanEmail).isPresent()) {
            throw ApiException.conflict("An account with this email already exists.");
        }
        String hash = encoder.encode(password);
        long id;
        try {
            id = transactions.execute(status -> {
                long created = users.insert(cleanEmail, hash, cleanName);
                users.recordSignIn(created);
                return created;
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("An account with this email already exists.");
        }
        log.info("Account {} created.", id);
        return users.findById(id).orElseThrow();
    }

    /**
     * Verifies credentials. Unknown email and wrong password give the same 401 and cost the same
     * bcrypt verification; too many recent failures for the email or the IP give a 429.
     */
    public UserRow authenticate(String email, String password, String clientIp) {
        String cleanEmail = email == null ? "" : email.strip();
        if (cleanEmail.isEmpty() || password == null || password.isEmpty()) {
            throw ApiException.badRequest("Enter your email and password.");
        }
        limits.check(RateLimit.SIGN_IN_PER_EMAIL, cleanEmail);
        limits.check(RateLimit.SIGN_IN_PER_IP, clientIp);
        Optional<UserRow> user = cleanEmail.length() > MAX_EMAIL_LENGTH ? Optional.empty() : users.findByEmail(cleanEmail);
        boolean usable = PasswordPolicy.fitsBcrypt(password);
        boolean matches;
        if (user.isPresent() && usable) {
            matches = encoder.matches(password, user.get().passwordHash());
        } else {
            encoder.matches(usable ? password : "x", dummyHash);
            matches = false;
        }
        if (!matches) {
            recordFailure(cleanEmail, clientIp);
            throw ApiException.unauthorized(INVALID_CREDENTIALS);
        }
        limits.clear(RateLimit.SIGN_IN_PER_EMAIL, cleanEmail);
        users.recordSignIn(user.get().id());
        return user.get();
    }

    /**
     * Changes the password of the signed-in user and returns the principal for the new session
     * version: every other session of the user is signed out, the caller refreshes its own.
     */
    public AccountPrincipal changePassword(
            AccountPrincipal principal, String currentPassword, String newPassword, String clientIp) {
        UserRow user = users.findById(principal.userId())
                .orElseThrow(() -> ApiException.unauthorized("Sign in to continue."));
        limits.check(RateLimit.SIGN_IN_PER_EMAIL, user.email());
        limits.check(RateLimit.SIGN_IN_PER_IP, clientIp);
        boolean matches = currentPassword != null && PasswordPolicy.fitsBcrypt(currentPassword)
                && encoder.matches(currentPassword, user.passwordHash());
        if (!matches) {
            recordFailure(user.email(), clientIp);
            throw ApiException.badRequest("The current password is incorrect.");
        }
        PasswordPolicy.check(newPassword, user.email());
        String hash = encoder.encode(newPassword);
        int version = transactions.execute(status -> {
            int updated = users.updatePassword(user.id(), hash);
            users.invalidateResetTokens(user.id());
            return updated;
        });
        log.info("Password changed for account {}; other sessions signed out.", user.id());
        return new AccountPrincipal(user.id(), user.email(), version);
    }

    /**
     * Emails a reset link when the email has an account; does nothing otherwise. The caller always
     * answers 202, so the response never reveals whether an account exists. 429 after too many
     * requests from the client IP; past {@link RateLimit#RESET_EMAIL_PER_ADDRESS} for one address
     * the request is accepted but nothing is sent (whether or not the account exists).
     */
    public void requestPasswordReset(String email, String clientIp) {
        limits.acquire(RateLimit.RESET_REQUEST_PER_IP, clientIp);
        String cleanEmail = email == null ? "" : email.strip();
        if (cleanEmail.isEmpty() || cleanEmail.length() > MAX_EMAIL_LENGTH) {
            return;
        }
        if (!limits.tryAcquire(RateLimit.RESET_EMAIL_PER_ADDRESS, cleanEmail)) {
            return;
        }
        Optional<UserRow> user = users.findByEmail(cleanEmail);
        if (user.isEmpty()) {
            return;
        }
        byte[] bytes = new byte[RESET_TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        transactions.executeWithoutResult(status -> {
            users.invalidateResetTokens(user.get().id());
            users.insertResetToken(user.get().id(), sha256(token), Instant.now().plus(RESET_TOKEN_LIFETIME));
        });
        notifier.sendResetLink(user.get().email(), user.get().displayName(), properties.resetLinkBase() + "?token=" + token);
    }

    /**
     * Sets a new password from a reset token: the token must be unused and unexpired, and is spent
     * by this call. Signs out every session of the user.
     */
    public void resetPassword(String token, String newPassword, String clientIp) {
        limits.acquire(RateLimit.RESET_CONFIRM_PER_IP, clientIp);
        if (token == null || token.isBlank() || token.length() > 100) {
            throw ApiException.badRequest(INVALID_RESET_LINK);
        }
        // Check what can be checked without the account first, so a typo doesn't spend the link.
        PasswordPolicy.check(newPassword, null);
        String hash = encoder.encode(newPassword);
        UserRow user = transactions.execute(status -> {
            long userId = users.consumeResetToken(sha256(token.strip()))
                    .orElseThrow(() -> ApiException.badRequest(INVALID_RESET_LINK));
            UserRow owner = users.findById(userId).orElseThrow(() -> ApiException.badRequest(INVALID_RESET_LINK));
            // Throwing here rolls the token back to unused.
            PasswordPolicy.check(newPassword, owner.email());
            users.updatePassword(userId, hash);
            users.invalidateResetTokens(userId);
            return owner;
        });
        // The owner proved control of the mailbox: earlier failed sign-ins no longer count.
        limits.clear(RateLimit.SIGN_IN_PER_EMAIL, user.email());
        log.info("Password reset for account {}; all sessions signed out.", user.id());
    }

    private void recordFailure(String email, String clientIp) {
        limits.record(RateLimit.SIGN_IN_PER_EMAIL, email);
        limits.record(RateLimit.SIGN_IN_PER_IP, clientIp);
    }

    public Optional<UserRow> find(long userId) {
        return users.findById(userId);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static String checkEmail(String email) {
        String clean = email == null ? "" : email.strip();
        if (clean.isEmpty()) {
            throw ApiException.badRequest("Enter an email address.");
        }
        if (clean.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(clean).matches()) {
            throw ApiException.badRequest("Enter a valid email address.");
        }
        return clean;
    }

    private static String checkDisplayName(String displayName) {
        String clean = displayName == null ? "" : displayName.strip();
        if (clean.isEmpty()) {
            throw ApiException.badRequest("Enter your name.");
        }
        if (clean.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw ApiException.badRequest("The name may be at most %d characters.".formatted(MAX_DISPLAY_NAME_LENGTH));
        }
        if (clean.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("The name contains invalid characters.");
        }
        return clean;
    }
}
