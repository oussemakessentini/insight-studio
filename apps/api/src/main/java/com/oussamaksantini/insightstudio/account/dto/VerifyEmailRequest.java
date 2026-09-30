package com.oussamaksantini.insightstudio.account.dto;

/** The secret token from an email-verification link. */
public record VerifyEmailRequest(String token) {
}
