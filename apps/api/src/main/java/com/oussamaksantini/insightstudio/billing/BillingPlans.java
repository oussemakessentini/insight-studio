package com.oussamaksantini.insightstudio.billing;

import com.oussamaksantini.insightstudio.billing.BillingProperties.PlanSettings;
import com.oussamaksantini.insightstudio.chart.ChartRules;
import com.oussamaksantini.insightstudio.customdashboard.DashboardRules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The configured plans (docs/billing-contract.md §1), checked at startup, and the rules that turn a
 * subscription into a plan. Startup fails for a missing {@code free} or {@code pro} plan, a missing
 * or negative limit, a limit above the absolute caps (charts {@value ChartRules#MAX_CHARTS},
 * dashboards {@value DashboardRules#MAX_DASHBOARDS}), or an unknown provider.
 */
@Component
public class BillingPlans {

    public static final String FREE = "free";
    public static final String PRO = "pro";

    public static final String FAKE = "fake";
    public static final String STRIPE = "stripe";
    public static final String NONE = "none";

    /** Statuses in which a subscription grants its plan; {@code past_due} is the grace while the provider retries. */
    public static final Set<String> ENTITLED = Set.of("active", "trialing", "past_due");
    /** Statuses after which a subscription can never be charged again: nothing to cancel. */
    public static final Set<String> ENDED = Set.of("canceled", "incomplete_expired");
    /** Every status business_subscriptions accepts (V18). */
    public static final Set<String> STATUSES = Set.of("none", "incomplete", "incomplete_expired", "trialing", "active",
            "past_due", "unpaid", "canceled", "paused");

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,19}$");

    private final String provider;
    private final Map<String, Plan> plans;
    private final Map<String, String> priceIds;

    public BillingPlans(BillingProperties properties) {
        this.provider = properties.provider() == null ? "" : properties.provider().strip().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of(FAKE, STRIPE, NONE).contains(provider)) {
            throw new IllegalStateException("insight.billing.provider (BILLING_PROVIDER) must be fake, stripe or none, not '"
                    + properties.provider() + "'");
        }
        List<String> problems = new ArrayList<>();
        Map<String, PlanSettings> configured = properties.plans();
        for (String required : List.of(FREE, PRO)) {
            if (!configured.containsKey(required)) {
                problems.add("plan '%s' is missing (insight.billing.plans.%s.*)".formatted(required, required));
            }
        }
        List<String> keys = new ArrayList<>(configured.keySet());
        // Free first, then by key: the order of the comparison table.
        keys.sort(Comparator.comparing((String key) -> !key.equals(FREE)).thenComparing(Comparator.naturalOrder()));
        Map<String, Plan> checked = new LinkedHashMap<>();
        Map<String, String> prices = new LinkedHashMap<>();
        for (String key : keys) {
            PlanSettings settings = configured.get(key);
            if (!KEY.matcher(key).matches()) {
                problems.add("plan key '%s' must be lowercase letters, digits or '_'".formatted(key));
                continue;
            }
            Plan.Limits limits = limits(key, settings.limits(), problems);
            String name = StringUtils.hasText(settings.name()) ? settings.name().strip() : capitalized(key);
            String price = StringUtils.hasText(settings.priceDisplay()) ? settings.priceDisplay().strip()
                    : key.equals(FREE) ? "Free" : "";
            if (limits != null) {
                checked.put(key, new Plan(key, name, price, limits));
            }
            if (!key.equals(FREE) && StringUtils.hasText(settings.providerPriceId())) {
                prices.put(key, settings.providerPriceId().strip());
            }
        }
        if (provider.equals(STRIPE) && !prices.containsKey(PRO)) {
            problems.add("insight.billing.plans.pro.provider-price-id (BILLING_PRO_PRICE_ID) is required with the stripe provider");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid billing plans (docs/billing-api.md): " + String.join("; ", problems) + ".");
        }
        this.plans = java.util.Collections.unmodifiableMap(checked);
        this.priceIds = Map.copyOf(prices);
    }

    private static Plan.Limits limits(String key, BillingProperties.Limits limits, List<String> problems) {
        if (limits == null) {
            problems.add("plan '%s' has no limits".formatted(key));
            return null;
        }
        int before = problems.size();
        int members = limit(key, "members", limits.members(), Integer.MAX_VALUE, problems);
        int stores = limit(key, "stores", limits.stores(), Integer.MAX_VALUE, problems);
        int charts = limit(key, "charts", limits.charts(), ChartRules.MAX_CHARTS, problems);
        int dashboards = limit(key, "dashboards", limits.dashboards(), DashboardRules.MAX_DASHBOARDS, problems);
        int imports = limit(key, "imports-per-month", limits.importsPerMonth(), Integer.MAX_VALUE, problems);
        return problems.size() == before ? new Plan.Limits(members, stores, charts, dashboards, imports) : null;
    }

    private static int limit(String key, String name, Integer value, int cap, List<String> problems) {
        if (value == null) {
            problems.add("insight.billing.plans.%s.limits.%s is missing".formatted(key, name));
            return 0;
        }
        if (value < 0) {
            problems.add("insight.billing.plans.%s.limits.%s must not be negative".formatted(key, name));
        } else if (value > cap) {
            problems.add("insight.billing.plans.%s.limits.%s (%d) exceeds the absolute cap of %d".formatted(key, name, value, cap));
        }
        return value;
    }

    private static String capitalized(String key) {
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    /** {@code fake}, {@code stripe} or {@code none}. */
    public String provider() {
        return provider;
    }

    /** Whether billing is on (a provider other than {@code none}). */
    public boolean enabled() {
        return !provider.equals(NONE);
    }

    /** Every plan, free first. */
    public List<Plan> all() {
        return List.copyOf(plans.values());
    }

    public Plan free() {
        return plans.get(FREE);
    }

    public Optional<Plan> find(String key) {
        return Optional.ofNullable(key == null ? null : plans.get(key));
    }

    /** A plan that can be bought: configured, not free (and, for Stripe, with a price). */
    public Optional<Plan> purchasable(String key) {
        return find(key).filter(plan -> !plan.key().equals(FREE))
                .filter(plan -> !provider.equals(STRIPE) || priceIds.containsKey(plan.key()));
    }

    /** The provider price of a paid plan; the fake uses a made-up one when none is configured. */
    public String priceId(Plan plan) {
        String configured = priceIds.get(plan.key());
        return configured != null ? configured : "price_fake_" + plan.key();
    }

    /** The plan a provider price is for, if any is configured (or the fake's made-up price). */
    public Optional<Plan> forPrice(String priceId) {
        if (priceId == null) {
            return Optional.empty();
        }
        for (Plan plan : plans.values()) {
            if (!plan.key().equals(FREE) && priceId.equals(priceId(plan))) {
                return Optional.of(plan);
            }
        }
        return Optional.empty();
    }

    /**
     * The plan a business is on: the subscription's plan while its status grants it (active, trialing,
     * past_due) and it comes from the configured provider; Free otherwise, and always with billing off.
     *
     * @param subscriptionProvider the provider of the stored subscription ({@code null}: none)
     */
    public Plan effective(String subscriptionProvider, String planKey, String status) {
        if (!enabled() || subscriptionProvider == null || !subscriptionProvider.equals(provider)
                || status == null || !ENTITLED.contains(status)) {
            return free();
        }
        return find(planKey).orElse(free());
    }

    /** A plan other than {@code plan} with a larger limit for {@code resource}, preferring the smallest step up. */
    public Optional<Plan> upgradeFor(Plan plan, PlanResource resource) {
        if (!enabled()) {
            return Optional.empty();
        }
        return plans.values().stream()
                .filter(other -> !other.key().equals(plan.key()) && !other.key().equals(FREE))
                .filter(other -> other.limit(resource) > plan.limit(resource))
                .min(Comparator.comparingInt(other -> other.limit(resource)));
    }
}
