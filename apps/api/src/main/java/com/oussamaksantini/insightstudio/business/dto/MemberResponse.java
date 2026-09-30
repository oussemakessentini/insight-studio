package com.oussamaksantini.insightstudio.business.dto;

import com.oussamaksantini.insightstudio.tenancy.Role;
import java.time.Instant;

/** @param since when the membership was created */
public record MemberResponse(long userId, String email, String displayName, Role role, Instant since) {
}
