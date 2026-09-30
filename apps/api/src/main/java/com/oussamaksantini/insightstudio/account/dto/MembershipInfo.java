package com.oussamaksantini.insightstudio.account.dto;

import com.oussamaksantini.insightstudio.tenancy.Role;

/** A business the signed-in user belongs to. */
public record MembershipInfo(long businessId, String name, String slug, Role role) {
}
