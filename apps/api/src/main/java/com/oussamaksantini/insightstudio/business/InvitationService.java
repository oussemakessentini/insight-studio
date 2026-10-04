package com.oussamaksantini.insightstudio.business;

import com.oussamaksantini.insightstudio.account.AccountPrincipal;
import com.oussamaksantini.insightstudio.account.AccountProperties;
import com.oussamaksantini.insightstudio.account.EmailVerificationService;
import com.oussamaksantini.insightstudio.account.UserQueries;
import com.oussamaksantini.insightstudio.audit.AuditAction;
import com.oussamaksantini.insightstudio.audit.AuditLog;
import com.oussamaksantini.insightstudio.billing.PlanLimits;
import com.oussamaksantini.insightstudio.billing.PlanResource;
import com.oussamaksantini.insightstudio.business.InvitationQueries.InvitationRow;
import com.oussamaksantini.insightstudio.business.dto.BusinessResponse;
import com.oussamaksantini.insightstudio.business.dto.InvitationPreviewResponse;
import com.oussamaksantini.insightstudio.business.dto.InvitationResponse;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.security.RateLimit;
import com.oussamaksantini.insightstudio.security.RateLimiter;
import com.oussamaksantini.insightstudio.tenancy.Memberships;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invitations to join a business (docs/auth.md, "Invitations").
 *
 * <ul>
 *   <li>Sending works the same whether or not the address has an account: the answer and the email
 *       are identical, so inviting never reveals who has an account.</li>
 *   <li>A link is single-use, expires after {@link #LIFETIME}, and can be accepted only by a
 *       signed-in account whose email is the invited address.</li>
 *   <li>The inviter must still hold the authority to grant the role when the link is used; an
 *       inviter who was removed or demoted leaves invitations that no longer work.</li>
 * </ul>
 *
 * <p>Rate limits are taken before any transaction starts (see {@code AccountService}).
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    static final Duration LIFETIME = Duration.ofDays(7);
    static final String INVALID = "This invitation is invalid or has expired.";
    static final String WRONG_ACCOUNT =
            "This invitation was sent to a different email address. Sign in with that address to accept it.";
    static final int MAX_OPEN_PER_BUSINESS = 100;
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final InvitationQueries invitations;
    private final BusinessQueries businesses;
    private final Memberships memberships;
    private final UserQueries users;
    private final InvitationNotifier notifier;
    private final AccountProperties links;
    private final RateLimiter limits;
    private final TransactionTemplate transactions;
    private final EmailVerificationService verification;
    private final AuditLog audit;
    private final PlanLimits planLimits;
    private final SecureRandom random = new SecureRandom();

    InvitationService(
            InvitationQueries invitations,
            BusinessQueries businesses,
            Memberships memberships,
            UserQueries users,
            InvitationNotifier notifier,
            AccountProperties links,
            RateLimiter limits,
            TransactionTemplate transactions,
            EmailVerificationService verification,
            AuditLog audit,
            PlanLimits planLimits) {
        this.invitations = invitations;
        this.businesses = businesses;
        this.memberships = memberships;
        this.users = users;
        this.notifier = notifier;
        this.links = links;
        this.limits = limits;
        this.transactions = transactions;
        this.verification = verification;
        this.audit = audit;
        this.planLimits = planLimits;
    }

    /** ADMIN+: the business's open invitations. */
    public List<InvitationResponse> list(AccountPrincipal caller, long businessId) {
        requireRole(caller, businessId, Role.ADMIN);
        return invitations.open(businessId).stream().map(InvitationService::response).toList();
    }

    /**
     * Invites {@code email}: OWNER invites any role, ADMIN invites VIEWERs and ADMINs. Replaces an
     * earlier invitation to the same address. 409 when the address already belongs to a member
     * (members are visible to the caller anyway).
     */
    public InvitationResponse invite(AccountPrincipal caller, long businessId, String email, String roleName) {
        Role callerRole = requireRole(caller, businessId, Role.ADMIN);
        verification.requireVerified(caller.userId());
        Role role = parseRole(roleName);
        if (role == Role.OWNER && callerRole != Role.OWNER) {
            throw ApiException.forbidden("You need the OWNER role for this.");
        }
        String cleanEmail = checkEmail(email);
        limits.acquire(RateLimit.INVITATIONS_PER_ACCOUNT, caller.getName());
        String token = newToken();
        Instant expiresAt = Instant.now().plus(LIFETIME);
        InvitationRow created = transactions.execute(status -> {
            businesses.lockBusiness(businessId);
            if (invitations.isMemberEmail(businessId, cleanEmail)) {
                throw ApiException.conflict("This person is already a member.");
            }
            invitations.revokeUnfinishedFor(businessId, cleanEmail);
            // An open invitation reserves a seat: members plus open invitations must stay within the plan.
            planLimits.requireRoom(businessId, PlanResource.MEMBERS);
            if (invitations.countOpen(businessId) >= MAX_OPEN_PER_BUSINESS) {
                throw ApiException.conflict(
                        "This business has %d open invitations; revoke some first.".formatted(MAX_OPEN_PER_BUSINESS));
            }
            long id = invitations.insert(businessId, cleanEmail, role, sha256(token), caller.userId(), expiresAt);
            InvitationRow row = invitations.find(businessId, id).orElseThrow();
            audit.record(businessId, caller.userId(), AuditAction.MEMBER_INVITED, id,
                    Map.of("email", row.email(), "role", role.name()));
            notifier.sendInvitation(businessId, row.email(), row.invitedByName(), row.businessName(), role,
                    links.link("/invite", token), row.expiresAt());
            return row;
        });
        log.info("Account {} invited someone to business {} as {} (invitation {}).", caller.userId(), businessId, role, created.id());
        return response(created);
    }

    /** ADMIN+: revokes an open invitation; revoking an OWNER invitation needs OWNER. */
    public void revoke(AccountPrincipal caller, long businessId, long invitationId) {
        Role callerRole = requireRole(caller, businessId, Role.ADMIN);
        verification.requireVerified(caller.userId());
        InvitationRow invitation = invitations.find(businessId, invitationId)
                .filter(InvitationRow::open)
                .orElseThrow(() -> ApiException.notFound("Invitation not found."));
        if (invitation.role() == Role.OWNER && callerRole != Role.OWNER) {
            throw ApiException.forbidden("You need the OWNER role for this.");
        }
        transactions.executeWithoutResult(status -> {
            if (invitations.revoke(invitationId)) {
                audit.record(businessId, caller.userId(), AuditAction.INVITATION_REVOKED, invitationId,
                        Map.of("email", invitation.email(), "role", invitation.role().name()));
            }
        });
        log.info("Account {} revoked invitation {} of business {}.", caller.userId(), invitationId, businessId);
    }

    /** Anyone holding the link: what it offers. 400 when it cannot be used (for any reason). */
    public InvitationPreviewResponse preview(String token, String clientIp) {
        limits.acquire(RateLimit.INVITATION_TOKEN_PER_IP, clientIp);
        InvitationRow invitation = usable(token, false);
        return new InvitationPreviewResponse(invitation.businessName(), invitation.role(), invitation.invitedByName(),
                invitation.email(), invitation.expiresAt());
    }

    /**
     * Joins the business with the invited role and spends the invitation. 400 when the link cannot
     * be used, 403 for an account with another email, 409 for an existing member (the last two
     * leave the invitation usable).
     */
    public BusinessResponse accept(AccountPrincipal caller, String token, String clientIp) {
        limits.acquire(RateLimit.INVITATION_TOKEN_PER_IP, clientIp);
        UserQueries.UserRow user = users.findById(caller.userId())
                .orElseThrow(() -> ApiException.unauthorized("Sign in to continue."));
        BusinessResponse joined = transactions.execute(status -> {
            InvitationRow invitation = usable(token, true);
            if (!invitation.email().equalsIgnoreCase(user.email())) {
                throw ApiException.forbidden(WRONG_ACCOUNT);
            }
            businesses.lockBusiness(invitation.businessId());
            if (memberships.role(user.id(), invitation.businessId()).isPresent()) {
                throw ApiException.conflict("You're already a member of this business.");
            }
            // Refused while the members alone fill the plan (e.g. after a downgrade); the link stays usable.
            planLimits.requireSeatForAcceptance(invitation.businessId());
            if (!businesses.insertMembership(user.id(), invitation.businessId(), invitation.role())) {
                throw ApiException.conflict("You're already a member of this business.");
            }
            invitations.markAccepted(invitation.id(), user.id());
            audit.record(invitation.businessId(), user.id(), AuditAction.INVITATION_ACCEPTED, user.id(),
                    Map.of("role", invitation.role().name()));
            // The invitation was emailed to this address: accepting it proves the address works.
            verification.verifiedByEmailLink(user.id());
            return memberships.forUser(user.id()).stream()
                    .filter(m -> m.businessId() == invitation.businessId())
                    .map(m -> new BusinessResponse(m.businessId(), m.name(), m.slug(), m.currency(), m.timeZone(), m.role()))
                    .findFirst().orElseThrow();
        });
        log.info("Account {} joined business {} as {} by invitation.", user.id(), joined.businessId(), joined.role());
        return joined;
    }

    /**
     * The open invitation for {@code token} whose inviter can still grant its role; otherwise the
     * same 400 whatever the reason (unknown, used, revoked, expired, inviter without authority).
     */
    private InvitationRow usable(String token, boolean lock) {
        if (token == null || token.isBlank() || token.length() > 100) {
            throw ApiException.badRequest(INVALID);
        }
        Optional<InvitationRow> found = invitations.findByToken(sha256(token.strip()), lock).filter(InvitationRow::open);
        InvitationRow invitation = found.orElseThrow(() -> ApiException.badRequest(INVALID));
        Optional<Role> inviterRole = memberships.role(invitation.invitedBy(), invitation.businessId());
        boolean authorized = inviterRole.isPresent() && inviterRole.get().atLeast(Role.ADMIN)
                && (invitation.role() != Role.OWNER || inviterRole.get() == Role.OWNER);
        if (!authorized) {
            throw ApiException.badRequest(INVALID);
        }
        return invitation;
    }

    /** The caller's role in the business: 404 when not a member, 403 when weaker than {@code minimum}. */
    private Role requireRole(AccountPrincipal caller, long businessId, Role minimum) {
        Role role = memberships.role(caller.userId(), businessId)
                .orElseThrow(() -> ApiException.notFound(BusinessService.NOT_FOUND));
        if (!role.atLeast(minimum)) {
            throw ApiException.forbidden("You need the %s role for this.".formatted(minimum));
        }
        return role;
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static InvitationResponse response(InvitationRow row) {
        return new InvitationResponse(row.id(), row.email(), row.role(), row.invitedByName(), row.createdAt(), row.expiresAt());
    }

    private static String checkEmail(String email) {
        String clean = email == null ? "" : email.strip();
        if (clean.isEmpty()) {
            throw ApiException.badRequest("Enter the email of the person to invite.");
        }
        if (clean.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(clean).matches()) {
            throw ApiException.badRequest("Enter a valid email address.");
        }
        return clean;
    }

    private static Role parseRole(String value) {
        if (value != null) {
            try {
                return Role.valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // fall through
            }
        }
        throw ApiException.badRequest("'role' must be one of OWNER, ADMIN, VIEWER.");
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
