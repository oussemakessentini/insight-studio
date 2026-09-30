package com.oussamaksantini.insightstudio.dashboard;

import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.dashboard.DashboardQueries.RevenueBucket;
import com.oussamaksantini.insightstudio.dashboard.DashboardQueries.Totals;
import com.oussamaksantini.insightstudio.dashboard.dto.DashboardContextResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.DashboardContextResponse.BusinessInfo;
import com.oussamaksantini.insightstudio.dashboard.dto.DashboardContextResponse.Access;
import com.oussamaksantini.insightstudio.dashboard.dto.RecentSalesResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.RevenueSeriesResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.RevenueSeriesResponse.Point;
import com.oussamaksantini.insightstudio.dashboard.dto.StoreOption;
import com.oussamaksantini.insightstudio.dashboard.dto.StoreSalesResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.StoreSalesResponse.StoreSales;
import com.oussamaksantini.insightstudio.dashboard.dto.SummaryResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.TopProductsResponse;
import com.oussamaksantini.insightstudio.reporting.Granularity;
import com.oussamaksantini.insightstudio.reporting.MetricValue;
import com.oussamaksantini.insightstudio.reporting.ReportCalculations;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import com.oussamaksantini.insightstudio.reporting.ReportingContext;
import com.oussamaksantini.insightstudio.store.StoreRepository;
import com.oussamaksantini.insightstudio.tenancy.BusinessAccess;
import com.oussamaksantini.insightstudio.tenancy.CurrentBusiness;
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final ReportingContext reporting;
    private final StoreRepository stores;
    private final DashboardQueries queries;
    private final CurrentBusiness current;

    DashboardService(
            ReportingContext reporting, StoreRepository stores, DashboardQueries queries, CurrentBusiness current) {
        this.reporting = reporting;
        this.stores = stores;
        this.queries = queries;
        this.current = current;
    }

    public DashboardContextResponse context() {
        Business business = reporting.currentBusiness();
        List<StoreOption> storeOptions = stores.findAllByBusinessIdOrderByNameAsc(business.getId()).stream()
                .map(s -> new StoreOption(s.getId(), s.getCode(), s.getName(), s.getCity()))
                .toList();
        return new DashboardContextResponse(
                new BusinessInfo(business.getName(), business.getSlug(), business.getCurrency(), business.getTimeZone()),
                storeOptions,
                reporting.saleDateRange(business.getId(), business.zoneId()),
                access(current.require()));
    }

    static Access access(BusinessAccess access) {
        if (access.demo()) {
            return new Access("DEMO", false, false, false, true, true);
        }
        // Until the address is verified nothing can be changed, whatever the role.
        boolean admin = access.role().atLeast(Role.ADMIN) && access.emailVerified();
        return new Access(access.role().name(), admin, admin, admin, !admin, access.emailVerified());
    }

    public SummaryResponse summary(LocalDate from, LocalDate to, Long storeId) {
        ReportFilter filter = reporting.resolveFilter(from, to, storeId);
        ReportFilter previous = filter.previous();
        Totals current = queries.totals(filter);
        Totals prior = queries.totals(previous);

        BigDecimal revenue = ReportCalculations.money(current.revenue());
        BigDecimal priorRevenue = ReportCalculations.money(prior.revenue());
        BigDecimal aov = ReportCalculations.averageOrderValue(revenue, current.orders());
        BigDecimal priorAov = ReportCalculations.averageOrderValue(priorRevenue, prior.orders());

        return new SummaryResponse(
                filter.period(),
                previous.period(),
                filter.storeId(),
                MetricValue.of(revenue, priorRevenue),
                MetricValue.of(current.orders(), prior.orders()),
                MetricValue.of(current.units(), prior.units()),
                MetricValue.of(aov, priorAov));
    }

    public RevenueSeriesResponse revenue(LocalDate from, LocalDate to, Long storeId, String granularityParam) {
        ReportFilter filter = reporting.resolveFilter(from, to, storeId);
        Granularity granularity = reporting.resolveGranularity(granularityParam, filter);

        Map<LocalDate, RevenueBucket> byStart = queries.revenueByBucket(filter, granularity).stream()
                .collect(Collectors.toMap(RevenueBucket::bucketStart, Function.identity()));
        List<Point> points = ReportCalculations.buckets(filter.from(), filter.to(), granularity).stream()
                .map(b -> {
                    RevenueBucket row = byStart.get(b.start());
                    BigDecimal revenue = ReportCalculations.money(row == null ? BigDecimal.ZERO : row.revenue());
                    long orders = row == null ? 0 : row.orders();
                    return new Point(b.start(), revenue, orders, b.daysCovered(), b.bucketDays(), b.complete());
                })
                .toList();
        return new RevenueSeriesResponse(filter.period(), filter.storeId(), granularity, points);
    }

    public StoreSalesResponse salesByStore(LocalDate from, LocalDate to, Long storeId) {
        ReportFilter filter = reporting.resolveFilter(from, to, storeId);
        List<StoreSales> rows = queries.salesByStore(filter);
        BigDecimal total = ReportCalculations.money(rows.stream()
                .map(StoreSales::revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        List<StoreSales> withShares = rows.stream()
                .map(r -> new StoreSales(r.storeId(), r.code(), r.name(), r.city(), r.revenue(), r.orders(),
                        r.unitsSold(), ReportCalculations.sharePercent(r.revenue(), total)))
                .toList();
        return new StoreSalesResponse(filter.period(), total, withShares);
    }

    public TopProductsResponse topProducts(LocalDate from, LocalDate to, Long storeId, int limit) {
        ReportFilter filter = reporting.resolveFilter(from, to, storeId);
        return new TopProductsResponse(filter.period(), filter.storeId(), queries.topProducts(filter, limit));
    }

    public RecentSalesResponse recentSales(LocalDate from, LocalDate to, Long storeId, int limit) {
        ReportFilter filter = reporting.resolveFilter(from, to, storeId);
        return new RecentSalesResponse(filter.period(), filter.storeId(), queries.recentSales(filter, limit));
    }
}
