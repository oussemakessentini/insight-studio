package com.oussamaksantini.insightstudio.business;

import com.oussamaksantini.insightstudio.account.AccountPrincipal;
import com.oussamaksantini.insightstudio.account.EmailVerificationService;
import com.oussamaksantini.insightstudio.audit.AuditAction;
import com.oussamaksantini.insightstudio.audit.AuditLog;
import com.oussamaksantini.insightstudio.business.BusinessQueries.BusinessRow;
import com.oussamaksantini.insightstudio.business.BusinessQueries.MemberRow;
import com.oussamaksantini.insightstudio.business.dto.BusinessResponse;
import com.oussamaksantini.insightstudio.business.dto.BusinessSettingsResponse;
import com.oussamaksantini.insightstudio.business.dto.TimeZonePreviewResponse;
import com.oussamaksantini.insightstudio.business.dto.MemberResponse;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.tenancy.Memberships;
import com.oussamaksantini.insightstudio.tenancy.PublicDemo;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Businesses and their members (docs/accounts-contract.md §4, §5). The business in the path is
 * only a selector: the caller's membership in it is loaded first, and a business the caller does
 * not belong to is a 404 whatever the operation.
 */
@Service
public class BusinessService {

    private static final Logger log = LoggerFactory.getLogger(BusinessService.class);

    public static final String NOT_FOUND = "Business not found.";
    static final String MEMBER_NOT_FOUND = "Member not found.";
    static final String LAST_OWNER = "A business needs at least one owner.";
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_SLUG_BASE = 80;
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final BusinessQueries queries;
    private final Memberships memberships;
    private final PublicDemo demo;
    private final EmailVerificationService verification;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    BusinessService(
            BusinessQueries queries,
            Memberships memberships,
            PublicDemo demo,
            EmailVerificationService verification,
            AuditLog audit) {
        this.queries = queries;
        this.memberships = memberships;
        this.demo = demo;
        this.verification = verification;
        this.audit = audit;
    }

    /** The message of a refused currency change (docs/account-management-contract.md §1). */
    static String currencyLocked(String currency) {
        return ("The currency can't change once the business has products or sales: their amounts are in %s. "
                + "Create a new business for another currency.").formatted(currency);
    }

    @Transactional(readOnly = true)
    public List<BusinessResponse> list(AccountPrincipal caller) {
        return memberships.forUser(caller.userId()).stream()
                .map(m -> new BusinessResponse(m.businessId(), m.name(), m.slug(), m.currency(), m.timeZone(), m.role()))
                .toList();
    }

    /** Creates a business owned by the caller. The slug comes from the name and is unique. */
    @Transactional
    public BusinessResponse create(AccountPrincipal caller, String name, String currency, String timeZone) {
        verification.requireVerified(caller.userId());
        String cleanName = checkName(name);
        String cleanCurrency = checkCurrency(currency);
        String cleanZone = checkTimeZone(timeZone);
        String base = slugBase(cleanName);
        for (int attempt = 1; attempt <= 60; attempt++) {
            String slug = attempt == 1 ? base
                    : attempt <= 50 ? base + "-" + attempt
                    : base + "-" + Integer.toString(random.nextInt(1 << 30), 36);
            if (slug.equalsIgnoreCase(demo.reservedSlug())) {
                continue;
            }
            Optional<Long> id = queries.insertBusiness(cleanName, slug, cleanCurrency, cleanZone);
            if (id.isPresent()) {
                queries.insertMembership(caller.userId(), id.get(), Role.OWNER);
                audit.record(id.get(), caller.userId(), AuditAction.BUSINESS_CREATED, id.get(),
                        Map.of("name", cleanName, "currency", cleanCurrency, "timeZone", cleanZone));
                log.info("Business {} created by account {}.", id.get(), caller.userId());
                return new BusinessResponse(id.get(), cleanName, slug, cleanCurrency, cleanZone, Role.OWNER);
            }
        }
        throw ApiException.conflict("Could not find a free address for this business name; try another name.");
    }

    /**
     * OWNER: renames the business and/or changes its time zone and/or currency. The currency can only
     * change while the business holds no amounts (no products and no sales): 409 otherwise. Each real
     * change writes its audit event; sending the current value changes nothing.
     */
    @Transactional
    public BusinessResponse update(AccountPrincipal caller, long businessId, String name, String timeZone, String currency) {
        requireRole(caller, businessId, Role.OWNER);
        verification.requireVerified(caller.userId());
        if (name == null && timeZone == null && currency == null) {
            throw ApiException.badRequest("Nothing to update: send 'name', 'timeZone' and/or 'currency'.");
        }
        String newName = name == null ? null : checkName(name);
        String newZone = timeZone == null ? null : checkTimeZone(timeZone);
        String newCurrency = currency == null ? null : checkCurrency(currency);
        queries.lockBusiness(businessId);
        BusinessRow business = queries.find(businessId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        newName = newName == null ? business.name() : newName;
        newZone = newZone == null ? business.timeZone() : newZone;
        newCurrency = newCurrency == null ? business.currency() : newCurrency;
        if (!newCurrency.equals(business.currency()) && queries.hasMonetaryData(businessId)) {
            throw ApiException.conflict(currencyLocked(business.currency()));
        }
        queries.updateBusiness(businessId, newName, newZone, newCurrency);
        long actor = caller.userId();
        if (!newName.equals(business.name())) {
            audit.record(businessId, actor, AuditAction.BUSINESS_RENAMED, businessId, Map.of("from", business.name(), "to", newName));
        }
        if (!newZone.equals(business.timeZone())) {
            audit.record(businessId, actor, AuditAction.BUSINESS_TIME_ZONE_CHANGED, businessId,
                    Map.of("from", business.timeZone(), "to", newZone));
        }
        if (!newCurrency.equals(business.currency())) {
            audit.record(businessId, actor, AuditAction.BUSINESS_CURRENCY_CHANGED, businessId,
                    Map.of("from", business.currency(), "to", newCurrency));
            log.info("Account {} changed the currency of business {} to {}.", actor, businessId, newCurrency);
        }
        return new BusinessResponse(businessId, newName, business.slug(), newCurrency, newZone, Role.OWNER);
    }

    /** VIEWER+: the business's settings and whether its currency may still change. */
    @Transactional(readOnly = true)
    public BusinessSettingsResponse settings(AccountPrincipal caller, long businessId) {
        Role role = requireRole(caller, businessId, Role.VIEWER);
        BusinessRow business = queries.find(businessId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        boolean locked = queries.hasMonetaryData(businessId);
        return new BusinessSettingsResponse(businessId, business.name(), business.slug(), business.currency(),
                business.timeZone(), role, !locked, locked ? currencyLocked(business.currency()) : null, business.createdAt());
    }

    /**
     * OWNER: what changing the time zone to {@code timeZone} would do to the reports: how many sales
     * fall on another local day or month, and the months whose totals change (newest first, at most
     * {@value BusinessQueries#PREVIEW_MONTHS}). Nothing is changed.
     */
    @Transactional(readOnly = true)
    public TimeZonePreviewResponse timeZonePreview(AccountPrincipal caller, long businessId, String timeZone) {
        requireRole(caller, businessId, Role.OWNER);
        String to = checkTimeZone(timeZone);
        BusinessRow business = queries.find(businessId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        return queries.timeZonePreview(businessId, business.timeZone(), to);
    }

    /** ADMIN+: the business's members. */
    @Transactional(readOnly = true)
    public List<MemberResponse> members(AccountPrincipal caller, long businessId) {
        requireRole(caller, businessId, Role.ADMIN);
        return queries.members(businessId).stream().map(BusinessService::response).toList();
    }

    /** OWNER: changes a member's role; the last owner cannot be demoted. */
    @Transactional
    public MemberResponse changeRole(AccountPrincipal caller, long businessId, long userId, String roleName) {
        requireRole(caller, businessId, Role.OWNER);
        verification.requireVerified(caller.userId());
        Role role = parseRole(roleName);
        queries.lockBusiness(businessId);
        MemberRow target = queries.member(businessId, userId).orElseThrow(() -> ApiException.notFound(MEMBER_NOT_FOUND));
        if (target.role() == Role.OWNER && role != Role.OWNER && queries.countOwners(businessId) <= 1) {
            throw ApiException.conflict(LAST_OWNER);
        }
        queries.updateRole(businessId, userId, role);
        if (target.role() != role) {
            audit.record(businessId, caller.userId(), AuditAction.MEMBER_ROLE_CHANGED, userId,
                    Map.of("from", target.role().name(), "to", role.name()));
        }
        log.info("Account {} changed the role of account {} in business {} to {}.", caller.userId(), userId, businessId, role);
        return queries.member(businessId, userId).map(BusinessService::response).orElseThrow();
    }

    /**
     * Removes a member: OWNER removes anyone, ADMIN removes VIEWERs, anyone may leave; the last
     * owner can neither be removed nor leave.
     */
    @Transactional
    public void removeMember(AccountPrincipal caller, long businessId, long userId) {
        Role callerRole = requireRole(caller, businessId, Role.VIEWER);
        boolean self = userId == caller.userId();
        if (!self && !callerRole.atLeast(Role.ADMIN)) {
            throw ApiException.forbidden("You need the ADMIN role for this.");
        }
        // Leaving is always allowed; removing someone else is a change to the business.
        if (!self) {
            verification.requireVerified(caller.userId());
        }
        queries.lockBusiness(businessId);
        MemberRow target = queries.member(businessId, userId).orElseThrow(() -> ApiException.notFound(MEMBER_NOT_FOUND));
        if (!self && callerRole == Role.ADMIN && target.role() != Role.VIEWER) {
            throw ApiException.forbidden("Admins can only remove viewers.");
        }
        if (target.role() == Role.OWNER && queries.countOwners(businessId) <= 1) {
            throw ApiException.conflict(LAST_OWNER);
        }
        queries.deleteMembership(businessId, userId);
        audit.record(businessId, caller.userId(), self ? AuditAction.MEMBER_LEFT : AuditAction.MEMBER_REMOVED, userId,
                Map.of("role", target.role().name()));
        log.info("Account {} removed account {} from business {}.", caller.userId(), userId, businessId);
    }

    /** The caller's role in the business: 404 when not a member, 403 when weaker than {@code minimum}. */
    private Role requireRole(AccountPrincipal caller, long businessId, Role minimum) {
        Role role = memberships.role(caller.userId(), businessId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        if (!role.atLeast(minimum)) {
            throw ApiException.forbidden("You need the %s role for this.".formatted(minimum));
        }
        return role;
    }

    private static MemberResponse response(MemberRow row) {
        return new MemberResponse(row.userId(), row.email(), row.displayName(), row.role(), row.since());
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

    private static String checkName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw ApiException.badRequest("Enter a business name.");
        }
        if (clean.length() > MAX_NAME_LENGTH) {
            throw ApiException.badRequest("The business name may be at most %d characters.".formatted(MAX_NAME_LENGTH));
        }
        if (clean.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("The business name contains invalid characters.");
        }
        return clean;
    }

    private static String checkCurrency(String currency) {
        String clean = currency == null ? "" : currency.strip().toUpperCase(Locale.ROOT);
        if (CURRENCY.matcher(clean).matches()) {
            try {
                Currency.getInstance(clean);
                return clean;
            } catch (IllegalArgumentException e) {
                // fall through
            }
        }
        throw ApiException.badRequest("'currency' must be an ISO 4217 code such as EUR or USD.");
    }

    static String checkTimeZone(String timeZone) {
        String clean = timeZone == null ? "" : timeZone.strip();
        if (!ZoneId.getAvailableZoneIds().contains(clean)) {
            throw ApiException.badRequest("'timeZone' must be an IANA time zone such as Europe/Paris.");
        }
        return clean;
    }

    static String slugBase(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_SLUG_BASE) {
            slug = slug.substring(0, MAX_SLUG_BASE).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "business" : slug;
    }
}
