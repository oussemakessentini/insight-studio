package com.oussamaksantini.insightstudio.business.dto;

import com.oussamaksantini.insightstudio.tenancy.Role;

/** A business with the caller's role in it. */
public record BusinessResponse(long businessId, String name, String slug, String currency, String timeZone, Role role) {
}
