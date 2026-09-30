package com.oussamaksantini.insightstudio.store.dto;

/** {@code POST /api/stores}: {@code city} is optional. */
public record CreateStoreRequest(String code, String name, String city) {
}
