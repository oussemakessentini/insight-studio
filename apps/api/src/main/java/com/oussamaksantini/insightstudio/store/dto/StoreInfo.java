package com.oussamaksantini.insightstudio.store.dto;

/** Identifying data for a store; {@code city} is {@code null} for stores without a location. */
public record StoreInfo(long id, String code, String name, String city) {
}
