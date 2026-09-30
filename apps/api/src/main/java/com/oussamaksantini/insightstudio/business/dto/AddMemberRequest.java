package com.oussamaksantini.insightstudio.business.dto;

/** Adds an existing account, by email, with {@code role} (OWNER, ADMIN or VIEWER). */
public record AddMemberRequest(String email, String role) {
}
