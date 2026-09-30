package com.oussamaksantini.insightstudio.account;

import com.oussamaksantini.insightstudio.account.UserQueries.UserRow;
import com.oussamaksantini.insightstudio.account.dto.AccountResponse;
import com.oussamaksantini.insightstudio.account.dto.ChangePasswordRequest;
import com.oussamaksantini.insightstudio.account.dto.ForgotPasswordRequest;
import com.oussamaksantini.insightstudio.account.dto.MembershipInfo;
import com.oussamaksantini.insightstudio.account.dto.ResetPasswordRequest;
import com.oussamaksantini.insightstudio.account.dto.SignInRequest;
import com.oussamaksantini.insightstudio.account.dto.SignUpRequest;
import com.oussamaksantini.insightstudio.account.dto.UserInfo;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.security.SessionAuthentication;
import com.oussamaksantini.insightstudio.tenancy.Memberships;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account endpoints (docs/accounts-contract.md §5). Sign-up, sign-in, forgot and reset are public;
 * sign-out and password change need a session. Every POST needs the CSRF header.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AccountService accounts;
    private final Memberships memberships;
    private final SessionAuthentication sessions;

    AuthController(AccountService accounts, Memberships memberships, SessionAuthentication sessions) {
        this.accounts = accounts;
        this.memberships = memberships;
        this.sessions = sessions;
    }

    @PostMapping("/sign-up")
    ResponseEntity<AccountResponse> signUp(
            @RequestBody SignUpRequest body, HttpServletRequest request, HttpServletResponse response) {
        UserRow user = accounts.signUp(body.email(), body.password(), body.displayName());
        sessions.signIn(principal(user), request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(new AccountResponse(info(user), List.of()));
    }

    @PostMapping("/sign-in")
    AccountResponse signIn(@RequestBody SignInRequest body, HttpServletRequest request, HttpServletResponse response) {
        UserRow user = accounts.authenticate(body.email(), body.password(), request.getRemoteAddr());
        sessions.signIn(principal(user), request, response);
        return new AccountResponse(info(user), memberships(user.id()));
    }

    @PostMapping("/sign-out")
    ResponseEntity<Void> signOut(HttpServletRequest request, HttpServletResponse response) {
        sessions.signOut(request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/change")
    ResponseEntity<Void> changePassword(
            @RequestBody ChangePasswordRequest body, HttpServletRequest request, HttpServletResponse response) {
        AccountPrincipal principal = CurrentAccount.get()
                .orElseThrow(() -> ApiException.unauthorized("Sign in to continue."));
        AccountPrincipal updated = accounts.changePassword(
                principal, body.currentPassword(), body.newPassword(), request.getRemoteAddr());
        sessions.refresh(updated, request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/forgot")
    ResponseEntity<Void> forgotPassword(@RequestBody ForgotPasswordRequest body) {
        accounts.requestPasswordReset(body.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password/reset")
    ResponseEntity<Void> resetPassword(@RequestBody ResetPasswordRequest body) {
        accounts.resetPassword(body.token(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    private List<MembershipInfo> memberships(long userId) {
        return memberships.forUser(userId).stream()
                .map(m -> new MembershipInfo(m.businessId(), m.name(), m.slug(), m.role()))
                .toList();
    }

    static AccountPrincipal principal(UserRow user) {
        return new AccountPrincipal(user.id(), user.email(), user.sessionVersion());
    }

    static UserInfo info(UserRow user) {
        return new UserInfo(user.id(), user.email(), user.displayName());
    }
}
