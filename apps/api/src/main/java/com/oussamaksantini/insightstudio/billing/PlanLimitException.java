package com.oussamaksantini.insightstudio.billing;

import com.oussamaksantini.insightstudio.common.web.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A creation refused by the business's plan (docs/billing-contract.md §2): a {@code 409} problem
 * detail with {@code code: "plan_limit"}, {@code resource}, {@code limit}, {@code used}, {@code plan}
 * (its key) and {@code upgradeAvailable}; the {@code detail} names the plan and what to do.
 */
public class PlanLimitException extends ApiException {

    public static final String CODE = "plan_limit";

    private final PlanResource resource;
    private final int limit;
    private final long used;
    private final String plan;
    private final boolean upgradeAvailable;

    public PlanLimitException(String detail, PlanResource resource, int limit, long used, String plan,
            boolean upgradeAvailable) {
        super(HttpStatus.CONFLICT, detail);
        this.resource = resource;
        this.limit = limit;
        this.used = used;
        this.plan = plan;
        this.upgradeAvailable = upgradeAvailable;
    }

    public PlanResource getResource() {
        return resource;
    }

    public int getLimit() {
        return limit;
    }

    public long getUsed() {
        return used;
    }

    @Override
    public Map<String, Object> getProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("code", CODE);
        properties.put("resource", resource.key());
        properties.put("limit", limit);
        properties.put("used", used);
        properties.put("plan", plan);
        properties.put("upgradeAvailable", upgradeAvailable);
        return properties;
    }
}
