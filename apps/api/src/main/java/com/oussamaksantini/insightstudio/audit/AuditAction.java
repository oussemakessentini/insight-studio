package com.oussamaksantini.insightstudio.audit;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Every audited action (docs/account-management-contract.md §2): its stored name, what it targets, and
 * the only keys its {@code details} may hold. {@link AuditLog} refuses any other key, so a password,
 * token, hash, link, email body, CSV content or file hash can never end up in the history by mistake.
 */
public enum AuditAction {

    BUSINESS_CREATED("business.created", "business", "name", "currency", "timeZone"),
    BUSINESS_RENAMED("business.renamed", "business", "from", "to"),
    BUSINESS_TIME_ZONE_CHANGED("business.time_zone_changed", "business", "from", "to"),
    BUSINESS_CURRENCY_CHANGED("business.currency_changed", "business", "from", "to"),
    BUSINESS_EXPORTED("business.exported", "business"),
    MEMBER_INVITED("member.invited", "invitation", "email", "role"),
    INVITATION_REVOKED("invitation.revoked", "invitation", "email", "role"),
    INVITATION_ACCEPTED("invitation.accepted", "user", "role"),
    MEMBER_ROLE_CHANGED("member.role_changed", "user", "from", "to"),
    MEMBER_REMOVED("member.removed", "user", "role"),
    MEMBER_LEFT("member.left", "user", "role"),
    MEMBER_ACCOUNT_DELETED("member.account_deleted", "user", "role"),
    IMPORT_COMPLETED("import.completed", "import", "kind", "mode", "fileName", "rows", "created", "updated", "errors"),
    IMPORT_REJECTED("import.rejected", "import", "kind", "mode", "fileName", "rows", "created", "updated", "errors"),
    CHART_CREATED("chart.created", "chart", "title", "revision"),
    CHART_UPDATED("chart.updated", "chart", "title", "revision"),
    CHART_DUPLICATED("chart.duplicated", "chart", "title", "fromChartId"),
    CHART_DELETED("chart.deleted", "chart", "title", "revision"),
    DASHBOARD_CREATED("dashboard.created", "dashboard", "name", "revision", "widgetCount"),
    DASHBOARD_UPDATED("dashboard.updated", "dashboard", "name", "revision", "widgetCount", "renamedFrom"),
    DASHBOARD_DUPLICATED("dashboard.duplicated", "dashboard", "name", "revision", "widgetCount"),
    DASHBOARD_DELETED("dashboard.deleted", "dashboard", "name", "revision", "widgetCount");

    /** The categories of {@code GET /api/businesses/{id}/audit?category=}. */
    public enum Category {
        BUSINESS(List.of("business.")),
        MEMBER(List.of("member.", "invitation.")),
        IMPORT(List.of("import.")),
        CHART(List.of("chart.")),
        DASHBOARD(List.of("dashboard."));

        private final List<String> prefixes;

        Category(List<String> prefixes) {
            this.prefixes = prefixes;
        }

        public List<String> prefixes() {
            return prefixes;
        }

        /** The category named {@code value} (any case), or {@code null} for an unknown one. */
        public static Category parse(String value) {
            for (Category category : values()) {
                if (category.name().equals(value.strip().toUpperCase(Locale.ROOT))) {
                    return category;
                }
            }
            return null;
        }
    }

    private final String action;
    private final String targetType;
    private final Set<String> allowedDetails;

    AuditAction(String action, String targetType, String... allowedDetails) {
        this.action = action;
        this.targetType = targetType;
        this.allowedDetails = Set.of(allowedDetails);
    }

    public String action() {
        return action;
    }

    public String targetType() {
        return targetType;
    }

    public Set<String> allowedDetails() {
        return allowedDetails;
    }
}
