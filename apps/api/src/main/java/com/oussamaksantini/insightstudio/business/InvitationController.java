package com.oussamaksantini.insightstudio.business;

import com.oussamaksantini.insightstudio.account.AccountPrincipal;
import com.oussamaksantini.insightstudio.account.CurrentAccount;
import com.oussamaksantini.insightstudio.business.dto.BusinessResponse;
import com.oussamaksantini.insightstudio.business.dto.InvitationPreviewResponse;
import com.oussamaksantini.insightstudio.business.dto.InvitationResponse;
import com.oussamaksantini.insightstudio.business.dto.InvitationTokenRequest;
import com.oussamaksantini.insightstudio.business.dto.InviteRequest;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invitations. Managing them needs a session and ADMIN or OWNER in the business named by the path;
 * previewing a link is public; accepting one needs a session. Tokens travel in request bodies,
 * never in URLs the server logs.
 */
@RestController
class InvitationController {

    private final InvitationService service;

    InvitationController(InvitationService service) {
        this.service = service;
    }

    @GetMapping("/api/businesses/{businessId}/invitations")
    List<InvitationResponse> list(@PathVariable @Positive long businessId) {
        return service.list(caller(), businessId);
    }

    @PostMapping("/api/businesses/{businessId}/invitations")
    ResponseEntity<InvitationResponse> invite(@PathVariable @Positive long businessId, @RequestBody InviteRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.invite(caller(), businessId, body.email(), body.role()));
    }

    @DeleteMapping("/api/businesses/{businessId}/invitations/{invitationId}")
    ResponseEntity<Void> revoke(@PathVariable @Positive long businessId, @PathVariable @Positive long invitationId) {
        service.revoke(caller(), businessId, invitationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/invitations/preview")
    InvitationPreviewResponse preview(@RequestBody InvitationTokenRequest body, HttpServletRequest request) {
        return service.preview(body.token(), request.getRemoteAddr());
    }

    @PostMapping("/api/invitations/accept")
    BusinessResponse accept(@RequestBody InvitationTokenRequest body, HttpServletRequest request) {
        return service.accept(caller(), body.token(), request.getRemoteAddr());
    }

    private static AccountPrincipal caller() {
        // The security configuration already requires a session for everything but the preview.
        return CurrentAccount.get().orElseThrow(() -> ApiException.unauthorized("Sign in to continue."));
    }
}
