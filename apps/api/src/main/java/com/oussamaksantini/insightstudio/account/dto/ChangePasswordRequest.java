package com.oussamaksantini.insightstudio.account.dto;

/** Password change form. {@link #toString()} never prints the passwords. */
public record ChangePasswordRequest(String currentPassword, String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[]";
    }
}
