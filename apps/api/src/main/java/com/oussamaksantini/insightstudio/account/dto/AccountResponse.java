package com.oussamaksantini.insightstudio.account.dto;

import java.util.List;

/** Answer to sign-up and sign-in: the signed-in user and the businesses they belong to. */
public record AccountResponse(UserInfo user, List<MembershipInfo> memberships) {
}
