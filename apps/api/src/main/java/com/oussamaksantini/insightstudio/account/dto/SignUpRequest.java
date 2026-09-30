package com.oussamaksantini.insightstudio.account.dto;

/** Sign-up form. {@link #toString()} never prints the password. */
public record SignUpRequest(String email, String password, String displayName) {

    @Override
    public String toString() {
        return "SignUpRequest[email=" + email + ", displayName=" + displayName + "]";
    }
}
