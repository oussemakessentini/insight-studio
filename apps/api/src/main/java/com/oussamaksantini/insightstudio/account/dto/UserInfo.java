package com.oussamaksantini.insightstudio.account.dto;

/** @param emailVerified whether the account proved it controls its email address */
public record UserInfo(long id, String email, String displayName, boolean emailVerified) {
}
