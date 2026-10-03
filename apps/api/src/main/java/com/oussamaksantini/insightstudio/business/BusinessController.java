package com.oussamaksantini.insightstudio.business;

import com.oussamaksantini.insightstudio.account.AccountPrincipal;
import com.oussamaksantini.insightstudio.account.CurrentAccount;
import com.oussamaksantini.insightstudio.business.dto.BusinessResponse;
import com.oussamaksantini.insightstudio.business.dto.BusinessSettingsResponse;
import com.oussamaksantini.insightstudio.business.dto.TimeZonePreviewResponse;
import com.oussamaksantini.insightstudio.business.dto.ChangeRoleRequest;
import com.oussamaksantini.insightstudio.business.dto.CreateBusinessRequest;
import com.oussamaksantini.insightstudio.business.dto.MemberResponse;
import com.oussamaksantini.insightstudio.business.dto.UpdateBusinessRequest;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's businesses and their members. Requires a session (never the public demo).
 * People join a business through invitations ({@link InvitationController}).
 */
@RestController
@RequestMapping("/api/businesses")
class BusinessController {

    private final BusinessService service;

    BusinessController(BusinessService service) {
        this.service = service;
    }

    @GetMapping
    List<BusinessResponse> list() {
        return service.list(caller());
    }

    @PostMapping
    ResponseEntity<BusinessResponse> create(@RequestBody CreateBusinessRequest body) {
        BusinessResponse created = service.create(caller(), body.name(), body.currency(), body.timeZone());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{businessId}")
    BusinessResponse update(@PathVariable @Positive long businessId, @RequestBody UpdateBusinessRequest body) {
        return service.update(caller(), businessId, body.name(), body.timeZone(), body.currency());
    }

    @GetMapping("/{businessId}/settings")
    BusinessSettingsResponse settings(@PathVariable @Positive long businessId) {
        return service.settings(caller(), businessId);
    }

    @GetMapping("/{businessId}/time-zone-preview")
    TimeZonePreviewResponse timeZonePreview(
            @PathVariable @Positive long businessId, @RequestParam(required = false) String timeZone) {
        return service.timeZonePreview(caller(), businessId, timeZone);
    }

    @GetMapping("/{businessId}/members")
    List<MemberResponse> members(@PathVariable @Positive long businessId) {
        return service.members(caller(), businessId);
    }

    @PatchMapping("/{businessId}/members/{userId}")
    MemberResponse changeRole(
            @PathVariable @Positive long businessId, @PathVariable @Positive long userId, @RequestBody ChangeRoleRequest body) {
        return service.changeRole(caller(), businessId, userId, body.role());
    }

    @DeleteMapping("/{businessId}/members/{userId}")
    ResponseEntity<Void> removeMember(@PathVariable @Positive long businessId, @PathVariable @Positive long userId) {
        service.removeMember(caller(), businessId, userId);
        return ResponseEntity.noContent().build();
    }

    private static AccountPrincipal caller() {
        // The security configuration already requires a session for /api/businesses/**.
        return CurrentAccount.get().orElseThrow(() -> ApiException.unauthorized("Sign in to continue."));
    }
}
