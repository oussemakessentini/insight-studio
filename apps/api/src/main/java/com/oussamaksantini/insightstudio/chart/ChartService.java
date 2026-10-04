package com.oussamaksantini.insightstudio.chart;

import com.oussamaksantini.insightstudio.audit.AuditAction;
import com.oussamaksantini.insightstudio.audit.AuditLog;
import com.oussamaksantini.insightstudio.billing.PlanLimits;
import com.oussamaksantini.insightstudio.billing.PlanResource;
import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.chart.ChartEngine.ChartFigures;
import com.oussamaksantini.insightstudio.chart.ChartEngine.ChartQuery;
import com.oussamaksantini.insightstudio.chart.ChartQueries.ChartRow;
import com.oussamaksantini.insightstudio.chart.ChartQueries.RevisionRow;
import com.oussamaksantini.insightstudio.chart.dto.ChartCatalogResponse;
import com.oussamaksantini.insightstudio.chart.dto.ChartResponse;
import com.oussamaksantini.insightstudio.chart.dto.ChartResult;
import com.oussamaksantini.insightstudio.chart.dto.ChartRevisionResponse;
import com.oussamaksantini.insightstudio.chart.dto.ChartSummaryResponse;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.common.web.FieldErrorsException;
import com.oussamaksantini.insightstudio.common.web.FieldErrorsException.FieldError;
import com.oussamaksantini.insightstudio.reporting.DateRange;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import com.oussamaksantini.insightstudio.reporting.ReportingContext;
import com.oussamaksantini.insightstudio.savedreport.PeriodResolver;
import com.oussamaksantini.insightstudio.tenancy.BusinessAccess;
import com.oussamaksantini.insightstudio.tenancy.CurrentBusiness;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Saved charts of the current business, their revisions, and running definitions
 * (docs/chart-builder-contract.md §4). Charts are always loaded by (id, business), with the business
 * from {@link CurrentBusiness}, so another business's id is a 404 everywhere. Reading and running
 * need a member (VIEWER+); previews and writes need {@code require(Role.ADMIN)}.
 *
 * <p>Every save stores a new immutable revision. {@code PUT} carries the revision the editor started
 * from ({@code expectedRevision}); if someone saved in between, it is a 409 instead of silently
 * overwriting their work. Only definitions that pass {@link ChartValidator} are stored, and a stored
 * one is validated again before it runs (a store or product may have been deleted since).
 *
 * <p>Runs happen in a read-only transaction; the SQL engine limits its statement there.
 */
@Service
@Transactional(readOnly = true)
public class ChartService {

    static final String NOT_FOUND = "Chart not found.";
    static final String STALE = "This chart was changed by someone else since you opened it. Reload it to see "
            + "the latest version, then make your changes again.";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CurrentBusiness current;
    private final ReportingContext reporting;
    private final ChartQueries queries;
    private final ChartValidator validator;
    private final ChartCatalog catalog;
    private final ChartEngines engines;
    private final ChartResults results;
    private final PeriodResolver periods;
    private final AuditLog audit;
    private final PlanLimits planLimits;

    ChartService(
            CurrentBusiness current,
            ReportingContext reporting,
            ChartQueries queries,
            ChartValidator validator,
            ChartCatalog catalog,
            ChartEngines engines,
            ChartResults results,
            PeriodResolver periods,
            AuditLog audit,
            PlanLimits planLimits) {
        this.current = current;
        this.reporting = reporting;
        this.queries = queries;
        this.validator = validator;
        this.catalog = catalog;
        this.engines = engines;
        this.results = results;
        this.periods = periods;
        this.audit = audit;
        this.planLimits = planLimits;
    }

    public ChartCatalogResponse catalog() {
        return catalog.catalog(reporting.currentBusiness().getId());
    }

    /**
     * Runs an unsaved definition (the builder's preview); its title may still be empty.
     *
     * @param engineHeader receives the engine's name before it runs (for {@code X-Report-Engine})
     */
    public ChartResult preview(JsonNode body, Consumer<String> engineHeader) {
        Business business = reporting.currentBusiness(Role.ADMIN);
        return run(validator.validate(body, business, false), business, engineHeader);
    }

    public List<ChartSummaryResponse> list() {
        Business business = reporting.currentBusiness();
        return queries.list(business.getId()).stream().map(ChartService::summary).toList();
    }

    public ChartResponse get(long id) {
        Business business = reporting.currentBusiness();
        return response(load(business, id));
    }

    /** Runs the current revision, or {@code revision} when given. */
    public ChartResult data(long id, Integer revision, Consumer<String> engineHeader) {
        Business business = reporting.currentBusiness();
        ChartRow chart = load(business, id);
        String definition = revision == null ? chart.definition() : loadRevision(business, id, revision).definition();
        return run(validator.validate(JSON.readTree(definition), business, true), business, engineHeader);
    }

    public List<ChartRevisionResponse> revisions(long id) {
        Business business = reporting.currentBusiness();
        load(business, id);
        return queries.revisions(business.getId(), id).stream()
                .map(r -> new ChartRevisionResponse(r.revision(), null, r.createdBy(), r.createdAt()))
                .toList();
    }

    public ChartRevisionResponse revision(long id, int revision) {
        Business business = reporting.currentBusiness();
        load(business, id);
        RevisionRow row = loadRevision(business, id, revision);
        return new ChartRevisionResponse(row.revision(), JSON.readTree(row.definition()), row.createdBy(), row.createdAt());
    }

    // ---------------------------------------------------------------- writes

    @Transactional
    public ChartResponse create(JsonNode body) {
        BusinessAccess access = current.require(Role.ADMIN);
        Business business = reporting.currentBusiness(Role.ADMIN);
        ChartDefinition definition = validator.validate(body, business, true);
        ChartRow created = load(business, insert(business, definition, access.userId(), true));
        audit.record(business.getId(), access.userId(), AuditAction.CHART_CREATED, created.id(),
                Map.of("title", created.title(), "revision", created.revision()));
        return response(created);
    }

    /** Saves {@code definition} as the next revision, if the chart is still at {@code expectedRevision}. */
    @Transactional
    public ChartResponse update(long id, JsonNode body) {
        BusinessAccess access = current.require(Role.ADMIN);
        Business business = reporting.currentBusiness(Role.ADMIN);
        ChartRow chart = load(business, id);
        if (body == null || !body.isObject()) {
            throw new FieldErrorsException(List.of(new FieldError("definition",
                    "Send {\"definition\": {...}, \"expectedRevision\": n}.")));
        }
        JsonNode expected = body.get("expectedRevision");
        if (expected == null || !expected.isIntegralNumber() || !expected.canConvertToInt() || expected.asInt() < 1) {
            throw new FieldErrorsException(List.of(new FieldError("expectedRevision",
                    "'expectedRevision' must be the revision you started editing from.")));
        }
        int expectedRevision = expected.asInt();
        if (chart.revision() != expectedRevision) {
            throw ApiException.conflict(STALE);
        }
        ChartDefinition definition = validator.validate(body.get("definition"), business, true);
        if (queries.titleTaken(business.getId(), definition.title(), id)) {
            throw titleConflict(definition.title());
        }
        int revision;
        try {
            revision = queries.advance(business.getId(), id, expectedRevision, definition.title(), access.userId())
                    .orElseThrow(() -> ApiException.conflict(STALE)); // saved by someone else in between
        } catch (DuplicateKeyException e) {
            throw titleConflict(definition.title());
        }
        queries.insertRevision(business.getId(), id, revision, definition.schemaVersion(),
                definition.toJson().toString(), access.userId());
        audit.record(business.getId(), access.userId(), AuditAction.CHART_UPDATED, id,
                Map.of("title", definition.title(), "revision", revision));
        return response(load(business, id));
    }

    /** A new chart (revision 1) with the current definition of {@code id}; titled "Copy of …" unless given. */
    @Transactional
    public ChartResponse duplicate(long id, JsonNode body) {
        BusinessAccess access = current.require(Role.ADMIN);
        Business business = reporting.currentBusiness(Role.ADMIN);
        ChartRow source = load(business, id);
        String title = null;
        if (body != null && !body.isNull()) {
            if (!body.isObject()) {
                throw new FieldErrorsException(List.of(new FieldError("title", "Send {\"title\": \"...\"} or no body.")));
            }
            List<FieldError> errors = new ArrayList<>();
            body.properties().stream().map(Map.Entry::getKey).filter(name -> !name.equals("title"))
                    .forEach(name -> errors.add(new FieldError(name, "Unknown field '%s'.".formatted(name))));
            if (!errors.isEmpty()) {
                throw new FieldErrorsException(errors);
            }
            JsonNode titleNode = body.get("title");
            if (titleNode != null && !titleNode.isNull()) {
                if (!titleNode.isString()) {
                    throw new FieldErrorsException(List.of(new FieldError("title", "'title' must be text.")));
                }
                title = titleNode.asString();
            }
        }
        boolean chosen = title != null;
        queries.lockBusiness(business.getId());
        if (!chosen) {
            title = copyTitle(business.getId(), source.title());
        }
        ObjectNode copy = (ObjectNode) JSON.readTree(source.definition());
        copy.put("title", title);
        ChartDefinition definition = validator.validate(copy, business, true);
        ChartRow created = load(business, insert(business, definition, access.userId(), chosen));
        audit.record(business.getId(), access.userId(), AuditAction.CHART_DUPLICATED, created.id(),
                Map.of("title", created.title(), "fromChartId", id));
        return response(created);
    }

    @Transactional
    public void delete(long id) {
        BusinessAccess access = current.require(Role.ADMIN);
        Business business = reporting.currentBusiness(Role.ADMIN);
        ChartRow chart = load(business, id);
        if (!queries.delete(business.getId(), id)) {
            throw ApiException.notFound(NOT_FOUND);
        }
        audit.record(business.getId(), access.userId(), AuditAction.CHART_DELETED, id,
                Map.of("title", chart.title(), "revision", chart.revision()));
    }

    // ---------------------------------------------------------------- helpers

    private ChartResult run(ChartDefinition definition, Business business, Consumer<String> engineHeader) {
        ChartEngine engine = engines.engine(definition.engine());
        engineHeader.accept(engine.name());
        DateRange period = periods.resolve(definition.range(), business.zoneId());
        ReportFilter filter = new ReportFilter(business.getId(), business.zoneId(), period.from(), period.to(), null);
        ChartFigures figures = engine.figures(
                new ChartQuery(filter, definition.groupBy(), definition.granularity(), definition.filters()));
        return results.build(definition, business, period, figures, engine.name());
    }

    /** Inserts a validated definition as a new chart (limit and title checked under the business lock). */
    private long insert(Business business, ChartDefinition definition, long userId, boolean checkTitle) {
        queries.lockBusiness(business.getId());
        if (queries.count(business.getId()) >= ChartRules.MAX_CHARTS) {
            throw ApiException.conflict("A business can have at most %d charts. Delete one before adding another."
                    .formatted(ChartRules.MAX_CHARTS));
        }
        planLimits.requireRoom(business.getId(), PlanResource.CHARTS);
        if (checkTitle && queries.titleTaken(business.getId(), definition.title(), null)) {
            throw titleConflict(definition.title());
        }
        try {
            return queries.insert(business.getId(), definition.title(), definition.schemaVersion(),
                    definition.toJson().toString(), userId);
        } catch (DuplicateKeyException e) {
            // Two requests raced for the same title; the unique index decided.
            throw titleConflict(definition.title());
        }
    }

    /** "Copy of <title>", then "Copy of <title> (2)", … : the first free one, within the length limit. */
    private String copyTitle(long businessId, String title) {
        for (int n = 1; ; n++) {
            String suffix = n == 1 ? "" : " (%d)".formatted(n);
            String base = "Copy of " + title;
            int room = ChartRules.MAX_TITLE_LENGTH - suffix.length();
            String candidate = (base.length() > room ? base.substring(0, room).stripTrailing() : base) + suffix;
            if (!queries.titleTaken(businessId, candidate, null)) {
                return candidate;
            }
        }
    }

    private ChartRow load(Business business, long id) {
        return queries.find(business.getId(), id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    private RevisionRow loadRevision(Business business, long id, int revision) {
        return queries.revision(business.getId(), id, revision).orElseThrow(() -> ApiException.notFound(
                "Revision %d of this chart was not found.".formatted(revision)));
    }

    private static ChartResponse response(ChartRow row) {
        return new ChartResponse(row.id(), row.title(), row.revision(), JSON.readTree(row.definition()),
                row.createdBy(), row.updatedBy(), row.createdAt(), row.updatedAt());
    }

    private static ChartSummaryResponse summary(ChartRow row) {
        JsonNode definition = JSON.readTree(row.definition());
        List<String> metrics = new ArrayList<>();
        definition.path("metrics").forEach(m -> metrics.add(m.asString()));
        return new ChartSummaryResponse(row.id(), row.title(), definition.path("visualization").asString(), metrics,
                definition.path("groupBy").asString(), row.revision(), row.updatedBy(), row.updatedAt());
    }

    private static ApiException titleConflict(String title) {
        return ApiException.conflict("A chart titled '%s' already exists.".formatted(title));
    }
}
