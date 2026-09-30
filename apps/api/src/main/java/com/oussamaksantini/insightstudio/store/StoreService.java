package com.oussamaksantini.insightstudio.store;

import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.dashboard.DashboardService;
import com.oussamaksantini.insightstudio.dashboard.dto.RevenueSeriesResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.TopProductsResponse;
import com.oussamaksantini.insightstudio.reporting.MetricValue;
import com.oussamaksantini.insightstudio.reporting.ReportCalculations;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import com.oussamaksantini.insightstudio.reporting.ReportingContext;
import com.oussamaksantini.insightstudio.store.StoreQueries.CategoryRow;
import com.oussamaksantini.insightstudio.store.StoreQueries.StoreRow;
import com.oussamaksantini.insightstudio.store.StoreQueries.Totals;
import com.oussamaksantini.insightstudio.store.dto.StoreDetailResponse;
import com.oussamaksantini.insightstudio.store.dto.StoreDetailResponse.CategorySales;
import com.oussamaksantini.insightstudio.store.dto.StoreInfo;
import com.oussamaksantini.insightstudio.store.dto.StoreListResponse;
import com.oussamaksantini.insightstudio.store.dto.StoreListResponse.StorePerformance;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-store performance. A store's revenue series and top products are exactly the dashboard's
 * restricted to that store, so they are delegated to {@link DashboardService} rather than
 * duplicated; the two views agree by construction.
 *
 * <p>Stores are created by ADMINs and OWNERs of the current business (catalog setup).
 */
@Service
@Transactional(readOnly = true)
public class StoreService {

    /** Store codes are matched exactly by CSV imports, so they are kept simple. */
    static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$");

    private final ReportingContext reporting;
    private final StoreRepository stores;
    private final StoreQueries queries;
    private final DashboardService dashboard;

    StoreService(ReportingContext reporting, StoreRepository stores, StoreQueries queries, DashboardService dashboard) {
        this.reporting = reporting;
        this.stores = stores;
        this.queries = queries;
        this.dashboard = dashboard;
    }

    /** Every store of the business; the global store filter does not apply here. */
    public StoreListResponse list(LocalDate from, LocalDate to) {
        ReportFilter filter = reporting.resolveFilter(from, to, null);
        ReportFilter previous = filter.previous();
        List<StoreRow> rows = queries.storesWithPrevious(filter, previous);
        BigDecimal total = rows.stream().map(StoreRow::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<StorePerformance> performance = rows.stream()
                .map(r -> new StorePerformance(
                        r.id(),
                        r.code(),
                        r.name(),
                        r.city(),
                        r.revenue(),
                        r.orders(),
                        r.units(),
                        ReportCalculations.averageOrderValue(r.revenue(), r.orders()),
                        ReportCalculations.sharePercent(r.revenue(), total),
                        ReportCalculations.percentChange(r.revenue(), r.previousRevenue())))
                .toList();
        return new StoreListResponse(filter.period(), previous.period(), performance);
    }

    public StoreDetailResponse detail(long storeId, LocalDate from, LocalDate to) {
        // Resolving the filter with the store id rejects unknown stores and other businesses' stores.
        ReportFilter filter = reporting.resolveFilter(from, to, storeId);
        Store store = stores.findById(storeId)
                .orElseThrow(() -> ApiException.notFound("Store %d was not found.".formatted(storeId)));
        ReportFilter previous = filter.previous();
        Totals current = queries.totals(filter);
        Totals prior = queries.totals(previous);

        BigDecimal aov = ReportCalculations.averageOrderValue(current.revenue(), current.orders());
        BigDecimal priorAov = ReportCalculations.averageOrderValue(prior.revenue(), prior.orders());

        List<CategoryRow> rows = queries.categories(filter);
        BigDecimal categoryTotal = rows.stream().map(CategoryRow::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<CategorySales> categories = rows.stream()
                .map(c -> new CategorySales(c.category(), c.revenue(), c.units(), c.orders(),
                        ReportCalculations.sharePercent(c.revenue(), categoryTotal)))
                .toList();

        return new StoreDetailResponse(
                new StoreInfo(store.getId(), store.getCode(), store.getName(), store.getCity()),
                filter.period(),
                previous.period(),
                MetricValue.of(current.revenue(), prior.revenue()),
                MetricValue.of(current.orders(), prior.orders()),
                MetricValue.of(current.units(), prior.units()),
                MetricValue.of(aov, priorAov),
                categories);
    }

    /** Same shape and rules as {@code /api/dashboard/revenue?storeId=}; unknown stores are a 404. */
    public RevenueSeriesResponse revenue(long storeId, LocalDate from, LocalDate to, String granularity) {
        return dashboard.revenue(from, to, storeId, granularity);
    }

    /** Same shape and rules as {@code /api/dashboard/top-products?storeId=}; unknown stores are a 404. */
    public TopProductsResponse topProducts(long storeId, LocalDate from, LocalDate to, int limit) {
        return dashboard.topProducts(from, to, storeId, limit);
    }

    /** ADMIN+: adds a store to the current business; 409 when the code is taken there. */
    @Transactional
    public StoreInfo create(String code, String name, String city) {
        Business business = reporting.currentBusiness(Role.ADMIN);
        String cleanCode = code == null ? "" : code.strip();
        if (!CODE.matcher(cleanCode).matches()) {
            throw ApiException.badRequest(
                    "'code' must be 1 to 50 letters, digits, '.', '_' or '-', starting with a letter or digit.");
        }
        String cleanName = text(name, "name", 200, true);
        String cleanCity = text(city, "city", 100, false);
        long id = queries.insertStore(business.getId(), cleanCode, cleanName, cleanCity)
                .orElseThrow(() -> ApiException.conflict("A store with code '%s' already exists.".formatted(cleanCode)));
        return new StoreInfo(id, cleanCode, cleanName, cleanCity);
    }

    private static String text(String value, String field, int maxLength, boolean required) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty()) {
            if (required) {
                throw ApiException.badRequest("'%s' is required.".formatted(field));
            }
            return null;
        }
        if (clean.length() > maxLength || clean.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("'%s' must be at most %d characters of text.".formatted(field, maxLength));
        }
        return clean;
    }
}
