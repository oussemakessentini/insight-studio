package com.oussamaksantini.insightstudio.accountdata;

import com.oussamaksantini.insightstudio.accountdata.dto.Dtos.AccountDeletionPreview;
import com.oussamaksantini.insightstudio.accountdata.dto.Dtos.BusinessDeletionPreview;
import com.oussamaksantini.insightstudio.accountdata.dto.Dtos.DeleteAccountRequest;
import com.oussamaksantini.insightstudio.accountdata.dto.Dtos.DeleteBusinessRequest;
import com.oussamaksantini.insightstudio.business.MemberAccess;
import com.oussamaksantini.insightstudio.security.SessionAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exports, deletion previews and deletions of businesses and of the signed-in account
 * (docs/account-management-contract.md §3, §4). Every endpoint needs a session (401 otherwise, also
 * for public demo visitors); the DELETEs need the CSRF header like every write.
 */
@RestController
class AccountDataController {

    private final BusinessDataService businesses;
    private final AccountDataService accounts;
    private final SessionAuthentication sessions;

    AccountDataController(BusinessDataService businesses, AccountDataService accounts, SessionAuthentication sessions) {
        this.businesses = businesses;
        this.accounts = accounts;
        this.sessions = sessions;
    }

    @GetMapping("/api/businesses/{businessId}/export")
    ResponseEntity<byte[]> exportBusiness(@PathVariable @Positive long businessId) {
        BusinessDataService.ExportFile file = businesses.export(MemberAccess.caller(), businessId);
        return attachment(file.fileName(), MediaType.parseMediaType("application/zip"), file.content());
    }

    @GetMapping("/api/businesses/{businessId}/deletion-preview")
    BusinessDeletionPreview businessDeletionPreview(@PathVariable @Positive long businessId) {
        return businesses.deletionPreview(MemberAccess.caller(), businessId);
    }

    @DeleteMapping("/api/businesses/{businessId}")
    ResponseEntity<Void> deleteBusiness(
            @PathVariable @Positive long businessId, @RequestBody(required = false) DeleteBusinessRequest body,
            HttpServletRequest request) {
        DeleteBusinessRequest b = body == null ? new DeleteBusinessRequest(null, null) : body;
        businesses.delete(MemberAccess.caller(), businessId, b.password(), b.confirmName(), request.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/account/export")
    ResponseEntity<byte[]> exportAccount() {
        AccountDataService.ExportFile file = accounts.export(MemberAccess.caller());
        return attachment(file.fileName(), MediaType.APPLICATION_JSON, file.content());
    }

    @GetMapping("/api/account/deletion-preview")
    AccountDeletionPreview accountDeletionPreview() {
        return accounts.deletionPreview(MemberAccess.caller());
    }

    /** {@code 204}; the session cookie is cleared (every session of the account is already gone). */
    @DeleteMapping("/api/account")
    ResponseEntity<Void> deleteAccount(
            @RequestBody(required = false) DeleteAccountRequest body, HttpServletRequest request, HttpServletResponse response) {
        DeleteAccountRequest b = body == null ? new DeleteAccountRequest(null, null) : body;
        accounts.delete(MemberAccess.caller(), b.password(), b.confirmEmail(), request.getRemoteAddr());
        sessions.signOut(request, response);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<byte[]> attachment(String fileName, MediaType type, byte[] content) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content);
    }
}
