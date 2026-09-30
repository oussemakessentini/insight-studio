package com.oussamaksantini.insightstudio.account.dto;

/** Password reset form. {@link #toString()} never prints the token or the password. */
public record ResetPasswordRequest(String token, String newPassword) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[]";
    }
}
