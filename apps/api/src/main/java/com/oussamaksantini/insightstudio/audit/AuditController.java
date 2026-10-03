package com.oussamaksantini.insightstudio.audit;

import com.oussamaksantini.insightstudio.audit.dto.AuditPageResponse;
import com.oussamaksantini.insightstudio.business.MemberAccess;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/businesses/{id}/audit}: the business's audit history, newest first (ADMIN+). */
@RestController
class AuditController {

    private final AuditService service;

    AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping("/api/businesses/{businessId}/audit")
    AuditPageResponse audit(
            @PathVariable @Positive long businessId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) String category) {
        return service.page(MemberAccess.caller(), businessId, limit, before, category);
    }
}
