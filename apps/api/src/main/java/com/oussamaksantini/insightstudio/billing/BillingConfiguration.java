package com.oussamaksantini.insightstudio.billing;

import com.oussamaksantini.insightstudio.account.AccountProperties;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Selects the billing provider ({@code insight.billing.provider}, {@code BILLING_PROVIDER}): {@code fake}
 * (refused with the {@code prod} profile), {@code stripe} (test mode only) or {@code none}.
 */
@Configuration(proxyBeanMethods = false)
class BillingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BillingConfiguration.class);

    @Bean
    BillingProvider billingProvider(BillingPlans plans, BillingProperties properties, Environment environment,
            NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions, ObjectProvider<BillingWebhooks> webhooks,
            AccountProperties web, Clock clock) {
        return switch (plans.provider()) {
            case BillingPlans.FAKE -> {
                if (environment.acceptsProfiles(Profiles.of("prod"))) {
                    throw new IllegalStateException("The fake billing provider is for development and tests only; with the "
                            + "prod profile set BILLING_PROVIDER to stripe (test mode) or none.");
                }
                log.info("Billing: fake provider (test mode, no real payments).");
                yield new FakeBillingProvider(jdbc, transactions, webhooks, plans, web, properties.fake(), clock);
            }
            case BillingPlans.STRIPE -> {
                StripeBillingProvider stripe = new StripeBillingProvider(properties.stripe(), plans, clock);
                log.info("Billing: Stripe in test mode.");
                yield stripe;
            }
            default -> {
                log.info("Billing is off (BILLING_PROVIDER=none): every business is on the Free plan.");
                yield new NoBillingProvider();
            }
        };
    }
}
