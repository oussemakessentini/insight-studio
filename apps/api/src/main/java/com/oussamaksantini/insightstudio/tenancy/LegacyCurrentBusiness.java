package com.oussamaksantini.insightstudio.tenancy;

import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.business.BusinessRepository;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import org.springframework.stereotype.Component;

/**
 * TEMPORARY (step 0): keeps today's behaviour — the first business, full access — so branches that
 * depend on {@link CurrentBusiness} compile and test before authentication lands. The
 * backend-auth branch deletes this class and provides the membership-based implementation.
 */
@Component
class LegacyCurrentBusiness implements CurrentBusiness {

    private final BusinessRepository businesses;

    LegacyCurrentBusiness(BusinessRepository businesses) {
        this.businesses = businesses;
    }

    @Override
    public BusinessAccess require() {
        Business business = businesses.findFirstByOrderByIdAsc()
                .orElseThrow(() -> ApiException.notFound("No business data found."));
        return new BusinessAccess(business.getId(), Role.OWNER, false, null);
    }

    @Override
    public BusinessAccess require(Role minimum) {
        return require();
    }
}
