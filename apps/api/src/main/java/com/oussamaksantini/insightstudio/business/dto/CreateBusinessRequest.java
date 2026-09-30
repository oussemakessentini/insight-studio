package com.oussamaksantini.insightstudio.business.dto;

/**
 * @param currency ISO 4217 code, e.g. {@code EUR}
 * @param timeZone IANA time zone, e.g. {@code Europe/Paris}
 */
public record CreateBusinessRequest(String name, String currency, String timeZone) {
}
