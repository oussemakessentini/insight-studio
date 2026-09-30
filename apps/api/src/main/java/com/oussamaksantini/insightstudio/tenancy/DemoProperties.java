package com.oussamaksantini.insightstudio.tenancy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;

/**
 * Public demo mode (docs/accounts-contract.md §6).
 *
 * @param publicAccess {@code insight.demo.public}: anonymous, read-only access to the demo business;
 *     {@code false} unless explicitly enabled (the {@code demo} profile enables it)
 * @param businessSlug {@code insight.demo.business-slug}: the business shown as the public demo
 */
@ConfigurationProperties("insight.demo")
public record DemoProperties(
        @Name("public") @DefaultValue("false") boolean publicAccess,
        @DefaultValue("fieldstone-apparel") String businessSlug) {
}
