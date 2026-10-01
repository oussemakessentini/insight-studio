package com.oussamaksantini.insightstudio.savedreport;

import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.report.ReportService;
import com.oussamaksantini.insightstudio.report.dto.CategoryReportResponse;
import com.oussamaksantini.insightstudio.report.dto.MonthlyReportResponse;
import com.oussamaksantini.insightstudio.reporting.DateRange;
import com.oussamaksantini.insightstudio.reporting.ReportingContext;
import com.oussamaksantini.insightstudio.savedreport.SavedReportQueries.SavedReportRow;
import com.oussamaksantini.insightstudio.savedreport.dto.SavedReportRequest;
import com.oussamaksantini.insightstudio.savedreport.dto.SavedReportResponse;
import com.oussamaksantini.insightstudio.store.StoreRepository;
import com.oussamaksantini.insightstudio.tenancy.BusinessAccess;
import com.oussamaksantini.insightstudio.tenancy.CurrentBusiness;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved report definitions of the current business (docs/saved-reports-contract.md). Definitions
 * are always loaded by (id, business), with the business from {@link CurrentBusiness}, so another
 * business's id is a 404 everywhere. Reading needs a member (VIEWER+); writing needs
 * {@code require(Role.ADMIN)}. Running a definition calls {@link ReportService} for the resolved
 * period, so its figures are those of {@code /api/reports/*}.
 */
@Service
@Transactional(readOnly = true)
public class SavedReportService {

    static final String NOT_FOUND = "Saved report not found.";
    static final int MAX_NAME_LENGTH = 120;

    private final CurrentBusiness current;
    private final ReportingContext reporting;
    private final StoreRepository stores;
    private final SavedReportQueries queries;
    private final PeriodResolver periods;
    private final ReportService reports;

    SavedReportService(
            CurrentBusiness current,
            ReportingContext reporting,
            StoreRepository stores,
            SavedReportQueries queries,
            PeriodResolver periods,
            ReportService reports) {
        this.current = current;
        this.reporting = reporting;
        this.stores = stores;
        this.queries = queries;
        this.periods = periods;
        this.reports = reports;
    }

    /** A definition together with the business it belongs to and its report for the resolved period. */
    record Run(
            Business business,
            SavedReportRow definition,
            SavedReportResponse savedReport,
            MonthlyReportResponse monthly,
            CategoryReportResponse categories) {

        public DateRange period() {
            return savedReport.period();
        }
    }

    public List<SavedReportResponse> list() {
        Business business = reporting.currentBusiness();
        return queries.list(business.getId()).stream().map(row -> response(row, business.zoneId())).toList();
    }

    public SavedReportResponse get(long id) {
        Business business = reporting.currentBusiness();
        return response(load(business, id), business.zoneId());
    }

    /** Runs the definition: its range resolved now, then the same report as {@code /api/reports/*}. */
    Run run(long id) {
        Business business = reporting.currentBusiness();
        SavedReportRow row = load(business, id);
        SavedReportResponse response = response(row, business.zoneId());
        DateRange period = response.period();
        return switch (row.kind()) {
            case MONTHLY -> new Run(business, row, response,
                    reports.monthly(period.from(), period.to(), row.storeId()), null);
            case CATEGORIES -> new Run(business, row, response,
                    null, reports.categories(period.from(), period.to(), row.storeId()));
        };
    }

    @Transactional
    public SavedReportResponse create(SavedReportRequest request) {
        BusinessAccess access = current.require(Role.ADMIN);
        Business business = reporting.currentBusiness(Role.ADMIN);
        Definition definition = validate(request, business);
        if (queries.nameTaken(business.getId(), definition.name(), null)) {
            throw nameConflict(definition.name());
        }
        long id;
        try {
            id = queries.insert(business.getId(), definition.name(), definition.kind(), definition.range(),
                    definition.storeId(), access.userId());
        } catch (DuplicateKeyException e) {
            // Two requests raced for the same name; the unique index decided.
            throw nameConflict(definition.name());
        }
        return response(load(business, id), business.zoneId());
    }

    /** Replaces the definition (a rename is a PUT with a new name). */
    @Transactional
    public SavedReportResponse update(long id, SavedReportRequest request) {
        Business business = reporting.currentBusiness(Role.ADMIN);
        load(business, id);
        Definition definition = validate(request, business);
        if (queries.nameTaken(business.getId(), definition.name(), id)) {
            throw nameConflict(definition.name());
        }
        boolean updated;
        try {
            updated = queries.update(business.getId(), id, definition.name(), definition.kind(), definition.range(),
                    definition.storeId());
        } catch (DuplicateKeyException e) {
            throw nameConflict(definition.name());
        }
        if (!updated) {
            throw ApiException.notFound(NOT_FOUND);
        }
        return response(load(business, id), business.zoneId());
    }

    @Transactional
    public void delete(long id) {
        Business business = reporting.currentBusiness(Role.ADMIN);
        if (!queries.delete(business.getId(), id)) {
            throw ApiException.notFound(NOT_FOUND);
        }
    }

    private SavedReportRow load(Business business, long id) {
        return queries.find(business.getId(), id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    private SavedReportResponse response(SavedReportRow row, ZoneId zone) {
        SavedRange range = row.range();
        return new SavedReportResponse(
                row.id(),
                row.name(),
                row.kind().code(),
                new SavedReportResponse.Range(
                        range.type(), range.isRelative() ? range.preset().code() : null, range.from(), range.to()),
                row.storeId(),
                row.storeName(),
                periods.resolve(range, zone),
                row.createdBy(),
                row.createdAt(),
                row.updatedAt());
    }

    private record Definition(String name, ReportKind kind, SavedRange range, Long storeId) {
    }

    private Definition validate(SavedReportRequest request, Business business) {
        if (request == null) {
            throw ApiException.badRequest("A saved report needs a name, a kind and a range.");
        }
        String name = checkName(request.name());
        ReportKind kind = ReportKind.fromCode(strip(request.kind()))
                .orElseThrow(() -> ApiException.badRequest("'kind' must be monthly or categories."));
        SavedRange range = checkRange(request.range());
        Long storeId = request.storeId();
        if (storeId != null && !stores.existsByIdAndBusinessId(storeId, business.getId())) {
            // Same answer for another business's store and for an unknown id.
            throw ApiException.notFound("Store %d was not found.".formatted(storeId));
        }
        return new Definition(name, kind, range, storeId);
    }

    static String checkName(String value) {
        String name = strip(value);
        if (name == null || name.isEmpty()) {
            throw ApiException.badRequest("Enter a name for the saved report.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw ApiException.badRequest("The name may be at most %d characters.".formatted(MAX_NAME_LENGTH));
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("The name contains invalid characters.");
        }
        return name;
    }

    static SavedRange checkRange(SavedReportRequest.Range range) {
        if (range == null) {
            throw ApiException.badRequest("'range' is required.");
        }
        String type = strip(range.type());
        if (SavedRange.RELATIVE.equals(type)) {
            RelativePreset preset = RelativePreset.fromCode(strip(range.preset()))
                    .orElseThrow(() -> ApiException.badRequest("'range.preset' must be one of %s.".formatted(
                            Arrays.stream(RelativePreset.values()).map(RelativePreset::code)
                                    .collect(Collectors.joining(", ")))));
            return SavedRange.relative(preset);
        }
        if (!SavedRange.FIXED.equals(type)) {
            throw ApiException.badRequest("'range.type' must be fixed or relative.");
        }
        LocalDate from = date(range.from(), "range.from");
        LocalDate to = date(range.to(), "range.to");
        // The same rules as the report API (ReportingContext.resolveFilter).
        if (from.isAfter(to)) {
            throw ApiException.badRequest("'from' (%s) must be on or before 'to' (%s).".formatted(from, to));
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > ReportingContext.MAX_RANGE_DAYS) {
            throw ApiException.badRequest(
                    "The date range may cover at most %d days.".formatted(ReportingContext.MAX_RANGE_DAYS));
        }
        return SavedRange.fixed(from, to);
    }

    private static LocalDate date(String value, String field) {
        String clean = strip(value);
        if (clean == null || clean.isEmpty()) {
            throw ApiException.badRequest("'%s' is required for a fixed range.".formatted(field));
        }
        try {
            return LocalDate.parse(clean);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("'%s' must be a date such as 2026-07-01.".formatted(field));
        }
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static ApiException nameConflict(String name) {
        return ApiException.conflict("A saved report named '%s' already exists.".formatted(name));
    }
}
