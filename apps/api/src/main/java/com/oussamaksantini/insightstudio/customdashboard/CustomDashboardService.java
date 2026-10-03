package com.oussamaksantini.insightstudio.customdashboard;

import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.common.web.FieldErrorsException;
import com.oussamaksantini.insightstudio.common.web.FieldErrorsException.FieldError;
import com.oussamaksantini.insightstudio.common.web.StaleRevisionException;
import com.oussamaksantini.insightstudio.customdashboard.CustomDashboardQueries.DashboardRow;
import com.oussamaksantini.insightstudio.customdashboard.CustomDashboardQueries.RevisionRow;
import com.oussamaksantini.insightstudio.customdashboard.DashboardLayoutValidator.Validated;
import com.oussamaksantini.insightstudio.customdashboard.dto.DashboardReferenceResponse;
import com.oussamaksantini.insightstudio.customdashboard.dto.DashboardResponse;
import com.oussamaksantini.insightstudio.customdashboard.dto.DashboardRevisionResponse;
import com.oussamaksantini.insightstudio.customdashboard.dto.DashboardSummaryResponse;
import com.oussamaksantini.insightstudio.customdashboard.dto.DashboardWidgetResponse;
import com.oussamaksantini.insightstudio.tenancy.BusinessAccess;
import com.oussamaksantini.insightstudio.tenancy.CurrentBusiness;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Custom dashboards of the current business and their revisions (docs/dashboards-contract.md §3–§5).
 * Dashboards are always loaded by (id, business), with the business from {@link CurrentBusiness}, so
 * another business's id is a 404 everywhere. Reading needs a member (VIEWER+); writes need
 * {@code require(Role.ADMIN)}.
 *
 * <p>Every save (a rename too) stores a new immutable revision and rewrites the dashboard's chart
 * references in the same transaction. {@code PUT} carries the revision the editor started from
 * ({@code expectedRevision}); if someone saved in between, it is a 409 that says who, instead of
 * silently overwriting their work. Only layouts that pass {@link DashboardLayoutValidator} are stored.
 *
 * <p>Widgets reference charts by id: a dashboard always shows its charts' current revisions, and a
 * deleted chart's widget stays in the layout, reported as missing.
 */
@Service
@Transactional(readOnly = true)
public class CustomDashboardService {

    static final String NOT_FOUND = "Dashboard not found.";
    static final String CHART_NOT_FOUND = "Chart not found.";
    static final String STALE = "This dashboard was changed by someone else since you opened it. Reload it to see "
            + "the latest version, then make your changes again.";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> CREATE_FIELDS = Set.of("name", "layout");
    private static final Set<String> UPDATE_FIELDS = Set.of("name", "layout", "expectedRevision");
    private static final Set<String> DUPLICATE_FIELDS = Set.of("name");

    private final CurrentBusiness current;
    private final CustomDashboardQueries queries;
    private final DashboardLayoutValidator validator;

    CustomDashboardService(CurrentBusiness current, CustomDashboardQueries queries, DashboardLayoutValidator validator) {
        this.current = current;
        this.queries = queries;
        this.validator = validator;
    }

    public List<DashboardSummaryResponse> list() {
        long businessId = current.require().businessId();
        return queries.list(businessId).stream()
                .map(r -> new DashboardSummaryResponse(r.id(), r.name(), r.revision(), r.widgetCount(), r.missingCount(),
                        r.updatedBy(), r.updatedAt()))
                .toList();
    }

    /** The current revision, or {@code revision} when given (read-only: its name, layout and author). */
    public DashboardResponse get(long id, Integer revision) {
        long businessId = current.require().businessId();
        DashboardRow dashboard = load(businessId, id);
        if (revision == null) {
            return response(businessId, dashboard);
        }
        RevisionRow row = queries.revision(businessId, id, revision).orElseThrow(() -> ApiException.notFound(
                "Revision %d of this dashboard was not found.".formatted(revision)));
        JsonNode layout = JSON.readTree(row.layout());
        return new DashboardResponse(dashboard.id(), row.name(), row.revision(), layout, widgets(businessId, layout),
                dashboard.createdBy(), row.createdBy(), dashboard.createdAt(), row.createdAt());
    }

    public List<DashboardRevisionResponse> revisions(long id) {
        long businessId = current.require().businessId();
        load(businessId, id);
        return queries.revisions(businessId, id).stream()
                .map(r -> new DashboardRevisionResponse(r.revision(), r.name(), r.widgetCount(), r.createdBy(), r.createdAt()))
                .toList();
    }

    /** The dashboards whose current layout places the chart (warned about before deleting it). */
    public List<DashboardReferenceResponse> dashboardsUsingChart(long chartId) {
        long businessId = current.require().businessId();
        if (!queries.chartExists(businessId, chartId)) {
            throw ApiException.notFound(CHART_NOT_FOUND);
        }
        return queries.dashboardsUsing(businessId, chartId);
    }

    // ---------------------------------------------------------------- writes

    /** A new dashboard (revision 1) with the given layout, or an empty one. */
    @Transactional
    public DashboardResponse create(JsonNode body) {
        BusinessAccess access = current.require(Role.ADMIN);
        long businessId = access.businessId();
        requireObject(body, "Send {\"name\": \"...\", \"layout\": {...}}.");
        JsonNode layoutNode = body.get("layout");
        Validated valid = validator.validate(body.get("name"), present(layoutNode) ? layoutNode : null, businessId,
                unknownFields(body, CREATE_FIELDS));
        DashboardLayout layout = valid.layout() == null ? DashboardLayout.empty() : valid.layout();
        return response(businessId, load(businessId, insert(businessId, valid.name(), layout, access.userId(), true)));
    }

    /**
     * Saves the name and layout as the next revision, if the dashboard is still at
     * {@code expectedRevision}.
     */
    @Transactional
    public DashboardResponse update(long id, JsonNode body) {
        BusinessAccess access = current.require(Role.ADMIN);
        long businessId = access.businessId();
        DashboardRow dashboard = load(businessId, id);
        requireObject(body, "Send {\"name\": \"...\", \"layout\": {...}, \"expectedRevision\": n}.");
        List<FieldError> errors = unknownFields(body, UPDATE_FIELDS);
        JsonNode expected = body.get("expectedRevision");
        if (expected == null || !expected.isIntegralNumber() || !expected.canConvertToInt() || expected.asInt() < 1) {
            errors.add(new FieldError("expectedRevision", "'expectedRevision' must be the revision you started editing from."));
            throw new FieldErrorsException(errors);
        }
        int expectedRevision = expected.asInt();
        if (dashboard.revision() != expectedRevision) {
            throw stale(dashboard);
        }
        JsonNode layoutNode = body.get("layout");
        if (!present(layoutNode)) {
            errors.add(new FieldError("layout", "Send the dashboard's layout (also to rename it)."));
        }
        Validated valid = validator.validate(body.get("name"), present(layoutNode) ? layoutNode : null, businessId, errors);
        if (queries.nameTaken(businessId, valid.name(), id)) {
            throw nameConflict(valid.name());
        }
        int revision;
        try {
            // Conditional on the revision: 0 rows when someone saved in between.
            revision = queries.advance(businessId, id, expectedRevision, valid.name(), access.userId())
                    .orElseThrow(() -> stale(load(businessId, id)));
        } catch (DuplicateKeyException e) {
            throw nameConflict(valid.name());
        }
        write(businessId, id, revision, valid.name(), valid.layout(), access.userId());
        return response(businessId, load(businessId, id));
    }

    /**
     * A new dashboard (revision 1) with the current layout of {@code id}, without the widgets of deleted
     * charts; named "Copy of …" unless given.
     */
    @Transactional
    public DashboardResponse duplicate(long id, JsonNode body) {
        BusinessAccess access = current.require(Role.ADMIN);
        long businessId = access.businessId();
        DashboardRow source = load(businessId, id);
        String name = null;
        if (body != null && !body.isNull()) {
            requireObject(body, "Send {\"name\": \"...\"} or no body.");
            List<FieldError> errors = unknownFields(body, DUPLICATE_FIELDS);
            JsonNode nameNode = body.get("name");
            if (present(nameNode)) {
                name = validator.validate(nameNode, null, businessId, errors).name();
            } else if (!errors.isEmpty()) {
                throw new FieldErrorsException(errors);
            }
        }
        boolean chosen = name != null;

        DashboardLayout layout = DashboardLayout.fromStored(JSON.readTree(source.layout()));
        Set<Long> charts = queries.chartsOfBusiness(businessId, layout.chartIds());
        Set<String> missing = layout.widgets().stream().filter(w -> !charts.contains(w.chartId()))
                .map(DashboardLayout.Widget::id).collect(Collectors.toSet());
        DashboardLayout copy = validator.layout(layout.without(missing).toJson(), businessId);

        queries.lockBusiness(businessId);
        if (!chosen) {
            name = copyName(businessId, source.name());
        }
        return response(businessId, load(businessId, insert(businessId, name, copy, access.userId(), chosen)));
    }

    @Transactional
    public void delete(long id) {
        long businessId = current.require(Role.ADMIN).businessId();
        if (!queries.delete(businessId, id)) {
            throw ApiException.notFound(NOT_FOUND);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Inserts a new dashboard at revision 1 (limit and name checked under the business lock). */
    private long insert(long businessId, String name, DashboardLayout layout, long userId, boolean checkName) {
        queries.lockBusiness(businessId);
        if (queries.count(businessId) >= DashboardRules.MAX_DASHBOARDS) {
            throw ApiException.conflict("A business can have at most %d dashboards. Delete one before adding another."
                    .formatted(DashboardRules.MAX_DASHBOARDS));
        }
        if (checkName && queries.nameTaken(businessId, name, null)) {
            throw nameConflict(name);
        }
        long id;
        try {
            id = queries.insert(businessId, name, userId);
        } catch (DuplicateKeyException e) {
            // Two requests raced for the same name; the unique index decided.
            throw nameConflict(name);
        }
        write(businessId, id, 1, name, layout, userId);
        return id;
    }

    /** Stores a revision and points the chart references at its charts (same transaction as the caller). */
    private void write(long businessId, long id, int revision, String name, DashboardLayout layout, long userId) {
        queries.insertRevision(businessId, id, revision, name, layout.schemaVersion(), layout.toJson().toString(), userId);
        queries.replaceChartRefs(businessId, id, layout.chartIds());
    }

    /** "Copy of <name>", then "Copy of <name> (2)", … : the first free one, within the length limit. */
    private String copyName(long businessId, String name) {
        for (int n = 1; ; n++) {
            String suffix = n == 1 ? "" : " (%d)".formatted(n);
            String base = "Copy of " + name;
            int room = DashboardRules.MAX_NAME_LENGTH - suffix.length();
            String candidate = (base.length() > room ? base.substring(0, room).stripTrailing() : base) + suffix;
            if (!queries.nameTaken(businessId, candidate, null)) {
                return candidate;
            }
        }
    }

    private DashboardRow load(long businessId, long id) {
        return queries.find(businessId, id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    private DashboardResponse response(long businessId, DashboardRow row) {
        JsonNode layout = JSON.readTree(row.layout());
        return new DashboardResponse(row.id(), row.name(), row.revision(), layout, widgets(businessId, layout),
                row.createdBy(), row.updatedBy(), row.createdAt(), row.updatedAt());
    }

    /** The layout's widgets with their charts as they are now ({@code null} and missing when deleted). */
    private List<DashboardWidgetResponse> widgets(long businessId, JsonNode layoutNode) {
        DashboardLayout layout = DashboardLayout.fromStored(layoutNode);
        Map<Long, DashboardWidgetResponse.Chart> charts = queries.chartSummaries(businessId, layout.chartIds());
        return layout.widgets().stream().map(w -> {
            DashboardWidgetResponse.Chart chart = charts.get(w.chartId());
            return new DashboardWidgetResponse(w.id(), w.chartId(), chart == null, chart);
        }).toList();
    }

    private static StaleRevisionException stale(DashboardRow row) {
        return new StaleRevisionException(STALE, row.revision(), row.updatedBy(), row.updatedAt());
    }

    private static void requireObject(JsonNode body, String hint) {
        if (body == null || !body.isObject()) {
            throw new FieldErrorsException(List.of(new FieldError("name", hint)));
        }
    }

    private static List<FieldError> unknownFields(JsonNode body, Set<String> known) {
        List<FieldError> errors = new ArrayList<>();
        body.properties().stream().map(Map.Entry::getKey).filter(name -> !known.contains(name))
                .forEach(name -> errors.add(new FieldError(name, "Unknown field '%s'.".formatted(name))));
        return errors;
    }

    /** A value other than missing or JSON {@code null}. */
    private static boolean present(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode();
    }

    private static ApiException nameConflict(String name) {
        return ApiException.conflict("A dashboard named '%s' already exists.".formatted(name));
    }
}
