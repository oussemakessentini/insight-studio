package com.oussamaksantini.insightstudio.account.dto;

/** The public demo business, present only when the public demo is enabled and available. */
public record DemoInfo(boolean enabled, Long businessId, String name) {
}
