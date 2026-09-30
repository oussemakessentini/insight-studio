package com.oussamaksantini.insightstudio.account.dto;

import java.util.List;

/**
 * {@code GET /api/session}: who is signed in (if anyone), their businesses, and the public demo.
 *
 * @param user {@code null} when not signed in
 * @param memberships empty when not signed in
 * @param demo {@code null} unless the public demo is enabled and its business exists
 */
public record SessionResponse(boolean authenticated, UserInfo user, List<MembershipInfo> memberships, DemoInfo demo) {
}
