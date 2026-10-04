package com.oussamaksantini.insightstudio.store;

import com.oussamaksantini.insightstudio.billing.PlanLimits;
import com.oussamaksantini.insightstudio.billing.PlanResource;
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

    private final ReportingContext reporting;
    private final StoreRepository stores;
    private final StoreQueries queries;
    private final DashboardService dashboard;
    private final PlanLimits planLimits;

    StoreService(ReportingContext reporting, StoreRepository stores, StoreQueries queries, DashboardService dashboard,
            PlanLimits planLimits) {
        this.reporting = reporting;
        this.stores = stores;
        this.queries = queries;
        this.dashboard = dashboard;
        this.planLimits = planLimits;
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

    /** ADMIN+: adds a store to the current business; 409 when the code is taken there or the plan is full. */
    @Transactional
    public StoreInfo create(String code, String name, String city) {
        Business business = reporting.currentBusiness(Role.ADMIN);
        // The same rules as the stores CSV import (StoreFields).
        String cleanCode = StoreFields.code(code);
        String cleanName = StoreFields.name(name);
        String cleanCity = StoreFields.city(city);
        planLimits.requireRoom(business.getId(), PlanResource.STORES);
        long id = queries.insertStore(business.getId(), cleanCode, cleanName, cleanCity)
                .orElseThrow(() -> ApiException.conflict("A store with code '%s' already exists.".formatted(cleanCode)));
        return new StoreInfo(id, cleanCode, cleanName, cleanCity);
    }
}
