package com.oussamaksantini.insightstudio.dashboard;

import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.business.BusinessRepository;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.dashboard.DashboardQueries.RevenueBucket;
import com.oussamaksantini.insightstudio.dashboard.DashboardQueries.Totals;
import com.oussamaksantini.insightstudio.dashboard.dto.DashboardContextResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.DashboardContextResponse.BusinessInfo;
import com.oussamaksantini.insightstudio.dashboard.dto.DateRange;
import com.oussamaksantini.insightstudio.dashboard.dto.MetricValue;
import com.oussamaksantini.insightstudio.dashboard.dto.RecentSalesResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.RevenueSeriesResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.RevenueSeriesResponse.Point;
import com.oussamaksantini.insightstudio.dashboard.dto.StoreOption;
import com.oussamaksantini.insightstudio.dashboard.dto.StoreSalesResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.StoreSalesResponse.StoreSales;
import com.oussamaksantini.insightstudio.dashboard.dto.SummaryResponse;
import com.oussamaksantini.insightstudio.dashboard.dto.TopProductsResponse;
import com.oussamaksantini.insightstudio.store.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    /** Window used when the client omits {@code from}. */
    static final int DEFAULT_RANGE_DAYS = 30;
    /** Longest window a single request may cover. */
    static final int MAX_RANGE_DAYS = 366 * 3;

    private final BusinessRepository businesses;
    private final StoreRepository stores;
    private final DashboardQueries queries;
    private final DashboardProperties properties;

    DashboardService(
            BusinessRepository businesses,
            StoreRepository stores,
            DashboardQueries queries,
            DashboardProperties properties) {
        this.businesses = businesses;
        this.stores = stores;
        this.queries = queries;
        this.properties = properties;
    }

    public DashboardContextResponse context() {
        Business business = currentBusiness();
        List<StoreOption> storeOptions = stores.findAllByBusinessIdOrderByNameAsc(business.getId()).stream()
                .map(s -> new StoreOption(s.getId(), s.getCode(), s.getName(), s.getCity()))
                .toList();
        return new DashboardContextResponse(
                new BusinessInfo(business.getName(), business.getSlug(), business.getCurrency(), business.getTimeZone()),
                storeOptions,
                queries.saleDateRange(business.getId(), business.zoneId()));
    }

    public SummaryResponse summary(LocalDate from, LocalDate to, Long storeId) {
        ReportFilter filter = resolveFilter(from, to, storeId);
        DateRange previous = DashboardCalculations.previousPeriod(filter.from(), filter.to());
        Totals current = queries.totals(filter);
        Totals prior = queries.totals(filter.withPeriod(previous.from(), previous.to()));

        BigDecimal revenue = DashboardCalculations.money(current.revenue());
        BigDecimal priorRevenue = DashboardCalculations.money(prior.revenue());
        BigDecimal aov = DashboardCalculations.averageOrderValue(revenue, current.orders());
        BigDecimal priorAov = DashboardCalculations.averageOrderValue(priorRevenue, prior.orders());

        return new SummaryResponse(
                period(filter),
                previous,
                filter.storeId(),
                metric(revenue, priorRevenue),
                metric(BigDecimal.valueOf(current.orders()), BigDecimal.valueOf(prior.orders())),
                metric(BigDecimal.valueOf(current.units()), BigDecimal.valueOf(prior.units())),
                metric(aov, priorAov));
    }

    public RevenueSeriesResponse revenue(LocalDate from, LocalDate to, Long storeId, String granularityParam) {
        ReportFilter filter = resolveFilter(from, to, storeId);
        Granularity granularity = granularityParam == null || granularityParam.isBlank()
                ? Granularity.auto(filter.days())
                : parseGranularity(granularityParam);

        Map<LocalDate, RevenueBucket> byStart = queries.revenueByBucket(filter, granularity).stream()
                .collect(Collectors.toMap(RevenueBucket::bucketStart, Function.identity()));
        List<Point> points = DashboardCalculations.bucketStarts(filter.from(), filter.to(), granularity).stream()
                .map(start -> {
                    RevenueBucket bucket = byStart.get(start);
                    int bucketDays = DashboardCalculations.bucketDays(start, granularity);
                    int covered = DashboardCalculations.daysCovered(start, granularity, filter.from(), filter.to());
                    BigDecimal revenue = DashboardCalculations.money(bucket == null ? BigDecimal.ZERO : bucket.revenue());
                    long orders = bucket == null ? 0 : bucket.orders();
                    return new Point(start, revenue, orders, covered, bucketDays, covered == bucketDays);
                })
                .toList();
        return new RevenueSeriesResponse(period(filter), filter.storeId(), granularity, points);
    }

    public StoreSalesResponse salesByStore(LocalDate from, LocalDate to, Long storeId) {
        ReportFilter filter = resolveFilter(from, to, storeId);
        List<StoreSales> rows = queries.salesByStore(filter);
        BigDecimal total = DashboardCalculations.money(rows.stream()
                .map(StoreSales::revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        List<StoreSales> withShares = rows.stream()
                .map(r -> new StoreSales(r.storeId(), r.code(), r.name(), r.city(), r.revenue(), r.orders(),
                        r.unitsSold(), DashboardCalculations.sharePercent(r.revenue(), total)))
                .toList();
        return new StoreSalesResponse(period(filter), total, withShares);
    }

    public TopProductsResponse topProducts(LocalDate from, LocalDate to, Long storeId, int limit) {
        ReportFilter filter = resolveFilter(from, to, storeId);
        return new TopProductsResponse(period(filter), filter.storeId(), queries.topProducts(filter, limit));
    }

    public RecentSalesResponse recentSales(LocalDate from, LocalDate to, Long storeId, int limit) {
        ReportFilter filter = resolveFilter(from, to, storeId);
        return new RecentSalesResponse(period(filter), filter.storeId(), queries.recentSales(filter, limit));
    }

    /**
     * Applies defaults and validates the request. A missing {@code to} means the last day with sales
     * (or today when there are none); a missing {@code from} means {@value #DEFAULT_RANGE_DAYS} days ending at {@code to}.
     */
    ReportFilter resolveFilter(LocalDate from, LocalDate to, Long storeId) {
        Business business = currentBusiness();
        ZoneId zone = business.zoneId();

        if (storeId != null && !stores.existsByIdAndBusinessId(storeId, business.getId())) {
            throw ApiException.notFound("Store %d was not found.".formatted(storeId));
        }

        LocalDate resolvedTo = to;
        if (resolvedTo == null) {
            DateRange dataRange = queries.saleDateRange(business.getId(), zone);
            LocalDate latest = dataRange != null ? dataRange.to() : LocalDate.now(zone);
            resolvedTo = from != null && from.isAfter(latest) ? from : latest;
        }
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusDays(DEFAULT_RANGE_DAYS - 1);

        ReportFilter filter = new ReportFilter(business.getId(), zone, resolvedFrom, resolvedTo, storeId);
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw ApiException.badRequest("'from' (%s) must be on or before 'to' (%s).".formatted(resolvedFrom, resolvedTo));
        }
        if (filter.days() > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("The date range may cover at most %d days.".formatted(MAX_RANGE_DAYS));
        }
        return filter;
    }

    private Business currentBusiness() {
        String slug = properties.businessSlug();
        if (StringUtils.hasText(slug)) {
            return businesses.findBySlug(slug)
                    .orElseThrow(() -> ApiException.notFound("Business '%s' was not found.".formatted(slug)));
        }
        return businesses.findFirstByOrderByIdAsc()
                .orElseThrow(() -> ApiException.notFound(
                        "No business data found. Start the API with the 'demo' profile to load sample data."));
    }

    private static Granularity parseGranularity(String value) {
        try {
            return Granularity.fromParam(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private static DateRange period(ReportFilter filter) {
        return new DateRange(filter.from(), filter.to());
    }

    private static MetricValue metric(BigDecimal current, BigDecimal previous) {
        return new MetricValue(current, previous, DashboardCalculations.percentChange(current, previous));
    }
}
