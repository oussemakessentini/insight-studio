package com.oussamaksantini.insightstudio.business.dto;

/** Invites {@code email} to join the business with {@code role} (OWNER, ADMIN or VIEWER). */
public record InviteRequest(String email, String role) {
}
