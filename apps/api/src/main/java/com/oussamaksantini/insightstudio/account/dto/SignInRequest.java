package com.oussamaksantini.insightstudio.account.dto;

/** Sign-in form. {@link #toString()} never prints the password. */
public record SignInRequest(String email, String password) {

    @Override
    public String toString() {
        return "SignInRequest[email=" + email + "]";
    }
}
