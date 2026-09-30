package com.oussamaksantini.insightstudio.analytics;

import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.reporting.ReportCalculations;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import com.oussamaksantini.insightstudio.reporting.ReportingContext;
import com.oussamaksantini.insightstudio.tenancy.BusinessAccess;
import com.oussamaksantini.insightstudio.tenancy.CurrentBusiness;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * Business analytics served by Cube. The business always comes from {@link CurrentBusiness}
 * (the resolved membership or public demo), never from request input; it is signed into the
 * Cube token, and Cube adds it as a mandatory filter. The query below filters on it as well.
 */
public class AnalyticsService {

    static final String NOT_CONFIGURED = "Analytics is not configured.";
    static final String SOURCE = "cube";

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final CurrentBusiness currentBusiness;
    private final ReportingContext reporting;
    private final CubeClient cube;

    /** @param cube {@code null} when {@code insight.cube.url} is not set */
    AnalyticsService(CurrentBusiness currentBusiness, ReportingContext reporting, CubeClient cube) {
        this.currentBusiness = currentBusiness;
        this.reporting = reporting;
        this.cube = cube;
    }

    public AnalyticsSummaryResponse summary(LocalDate from, LocalDate to, Long storeId) {
        BusinessAccess access = currentBusiness.require();
        if (cube == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED);
        }
        ReportFilter filter = reporting.resolveFilter(from, to, storeId);
        if (filter.businessId() != access.businessId()) {
            // ReportingContext must resolve the same business; never query Cube for another one.
            log.error("Reporting context resolved business {} but the request is for business {}",
                    filter.businessId(), access.businessId());
            throw new IllegalStateException("Business resolution mismatch");
        }

        List<Map<String, Object>> rows = cube.load(access.businessId(), summaryQuery(access.businessId(), filter));
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.getFirst();

        BigDecimal revenue = ReportCalculations.money(decimal(row.get("orders.revenue")));
        long orders = decimal(row.get("orders.count")).longValueExact();
        long units = decimal(row.get("orders.units")).longValueExact();
        return new AnalyticsSummaryResponse(
                filter.period(),
                revenue,
                orders,
                units,
                ReportCalculations.averageOrderValue(revenue, orders),
                SOURCE);
    }

    /** Totals over the filter's calendar dates in the business's time zone. */
    static Map<String, Object> summaryQuery(long businessId, ReportFilter filter) {
        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(Map.of("member", "orders.business_id", "operator", "equals", "values", List.of(String.valueOf(businessId))));
        if (filter.storeId() != null) {
            filters.add(Map.of("member", "orders.store_id", "operator", "equals", "values", List.of(String.valueOf(filter.storeId()))));
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("measures", List.of("orders.revenue", "orders.count", "orders.units"));
        query.put("timeDimensions", List.of(Map.of(
                "dimension", "orders.sold_at",
                "dateRange", List.of(filter.from().toString(), filter.to().toString()))));
        query.put("filters", filters);
        query.put("timezone", filter.zone().getId());
        return query;
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            log.warn("Cube returned a non-numeric value");
            throw CubeClient.unavailable();
        }
    }
}
