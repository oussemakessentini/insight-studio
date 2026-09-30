package com.oussamaksantini.insightstudio.account;

import com.oussamaksantini.insightstudio.common.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Password rules (docs/accounts-contract.md §1): 12 to 128 characters, not the email. bcrypt only
 * uses the first 72 bytes of a password and Spring Security refuses longer input, so a password
 * must also fit in 72 UTF-8 bytes (72 plain ASCII characters) instead of being silently truncated.
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 128;
    static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    static void check(String password, String email) {
        if (password == null || password.isEmpty()) {
            throw ApiException.badRequest("Enter a password.");
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw ApiException.badRequest(
                    "The password must be %d to %d characters long.".formatted(MIN_LENGTH, MAX_LENGTH));
        }
        if (!fitsBcrypt(password)) {
            throw ApiException.badRequest(
                    "The password is too long: at most %d bytes (%d plain letters or digits).".formatted(MAX_BYTES, MAX_BYTES));
        }
        if (email != null && password.strip().toLowerCase(Locale.ROOT).equals(email.strip().toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest("The password must not be your email address.");
        }
    }

    static boolean fitsBcrypt(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
