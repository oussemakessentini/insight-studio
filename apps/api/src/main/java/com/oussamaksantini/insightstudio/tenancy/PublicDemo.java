package com.oussamaksantini.insightstudio.tenancy;

import com.oussamaksantini.insightstudio.business.BusinessRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The business anonymous visitors may read when the public demo is enabled.
 *
 * <p>Defence in depth: a business that has members is never served as the public demo, even if its
 * slug matches the configured one, so a real business can't be exposed by a configuration mistake.
 * New businesses never get the configured demo slug either (see {@code BusinessService}).
 */
@Component
public class PublicDemo {

    private final DemoProperties properties;
    private final BusinessRepository businesses;
    private final Memberships memberships;

    PublicDemo(DemoProperties properties, BusinessRepository businesses, Memberships memberships) {
        this.properties = properties;
        this.businesses = businesses;
        this.memberships = memberships;
    }

    public record DemoBusiness(long businessId, String name) {
    }

    public boolean enabled() {
        return properties.publicAccess();
    }

    /** The configured slug, reserved so that no created business can take it. */
    public String reservedSlug() {
        return properties.businessSlug();
    }

    /** The demo business when the public demo is enabled and the business is available. */
    public Optional<DemoBusiness> find() {
        if (!properties.publicAccess() || !StringUtils.hasText(properties.businessSlug())) {
            return Optional.empty();
        }
        return businesses.findBySlug(properties.businessSlug())
                .filter(b -> !memberships.hasMembers(b.getId()))
                .map(b -> new DemoBusiness(b.getId(), b.getName()));
    }
}
