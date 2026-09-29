package com.oussamaksantini.insightstudio.reporting;

import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.business.BusinessRepository;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.store.StoreRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the business a report is about and turns raw request parameters into a validated
 * {@link ReportFilter}. Shared by the dashboard and product endpoints so filters behave identically.
 */
@Component
public class ReportingContext {

    /** Window used when the client omits {@code from}. */
    public static final int DEFAULT_RANGE_DAYS = 30;
    /** Longest window a single request may cover. */
    public static final int MAX_RANGE_DAYS = 366 * 3;

    private final BusinessRepository businesses;
    private final StoreRepository stores;
    private final NamedParameterJdbcTemplate jdbc;
    private final ReportingProperties properties;

    ReportingContext(
            BusinessRepository businesses,
            StoreRepository stores,
            NamedParameterJdbcTemplate jdbc,
            ReportingProperties properties) {
        this.businesses = businesses;
        this.stores = stores;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** The configured business, or the first one created when no slug is configured. */
    public Business currentBusiness() {
        String slug = properties.businessSlug();
        if (StringUtils.hasText(slug)) {
            return businesses.findBySlug(slug)
                    .orElseThrow(() -> ApiException.notFound("Business '%s' was not found.".formatted(slug)));
        }
        return businesses.findFirstByOrderByIdAsc()
                .orElseThrow(() -> ApiException.notFound(
                        "No business data found. Start the API with the 'demo' profile to load sample data."));
    }

    /**
     * Applies defaults and validates the request. A missing {@code to} means the last day with sales
     * (or today when there are none); a missing {@code from} means {@value #DEFAULT_RANGE_DAYS} days ending at {@code to}.
     */
    public ReportFilter resolveFilter(LocalDate from, LocalDate to, Long storeId) {
        Business business = currentBusiness();
        ZoneId zone = business.zoneId();

        if (storeId != null && !stores.existsByIdAndBusinessId(storeId, business.getId())) {
            throw ApiException.notFound("Store %d was not found.".formatted(storeId));
        }

        LocalDate resolvedTo = to;
        if (resolvedTo == null) {
            DateRange dataRange = saleDateRange(business.getId(), zone);
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

    /** Parses an optional granularity parameter, choosing one from the range length when absent. */
    public Granularity resolveGranularity(String param, ReportFilter filter) {
        if (param == null || param.isBlank()) {
            return Granularity.auto(filter.days());
        }
        try {
            return Granularity.fromParam(param);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** First and last local sale dates for the business, or {@code null} when it has no sales. */
    public DateRange saleDateRange(long businessId, ZoneId zone) {
        String sql = """
                SELECT MIN(s.sold_at) AS first_sale, MAX(s.sold_at) AS last_sale
                FROM sales s
                JOIN stores st ON st.id = s.store_id
                WHERE st.business_id = :businessId
                """;
        return jdbc.queryForObject(sql, Map.of("businessId", businessId), (rs, i) -> {
            OffsetDateTime first = rs.getObject("first_sale", OffsetDateTime.class);
            OffsetDateTime last = rs.getObject("last_sale", OffsetDateTime.class);
            if (first == null || last == null) {
                return null;
            }
            return new DateRange(
                    first.atZoneSameInstant(zone).toLocalDate(),
                    last.atZoneSameInstant(zone).toLocalDate());
        });
    }
}
