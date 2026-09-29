package com.oussamaksantini.insightstudio.product;

import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.product.ProductQueries.Criteria;
import com.oussamaksantini.insightstudio.product.ProductQueries.Totals;
import com.oussamaksantini.insightstudio.product.ProductQueries.TrendBucket;
import com.oussamaksantini.insightstudio.product.dto.ProductCategoriesResponse;
import com.oussamaksantini.insightstudio.product.dto.ProductDetailResponse;
import com.oussamaksantini.insightstudio.product.dto.ProductInfo;
import com.oussamaksantini.insightstudio.product.dto.ProductListResponse;
import com.oussamaksantini.insightstudio.product.dto.ProductListResponse.Item;
import com.oussamaksantini.insightstudio.product.dto.ProductSalesTrendResponse;
import com.oussamaksantini.insightstudio.product.dto.ProductSalesTrendResponse.Point;
import com.oussamaksantini.insightstudio.reporting.Granularity;
import com.oussamaksantini.insightstudio.reporting.MetricValue;
import com.oussamaksantini.insightstudio.reporting.ReportCalculations;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import com.oussamaksantini.insightstudio.reporting.ReportingContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ReportingContext reporting;
    private final ProductRepository products;
    private final ProductQueries queries;

    ProductService(ReportingContext reporting, ProductRepository products, ProductQueries queries) {
        this.reporting = reporting;
        this.products = products;
        this.queries = queries;
    }

    public record ListRequest(
            LocalDate from,
            LocalDate to,
            Long storeId,
            String query,
            String category,
            String sort,
            String direction,
            int page,
            int size) {
    }

    public ProductListResponse list(ListRequest request) {
        ReportFilter filter = reporting.resolveFilter(request.from(), request.to(), request.storeId());
        ProductSort sort = StringUtils.hasText(request.sort()) ? parse(() -> ProductSort.fromParam(request.sort())) : ProductSort.REVENUE;
        ProductSort.Direction direction = StringUtils.hasText(request.direction())
                ? parse(() -> ProductSort.Direction.fromParam(request.direction()))
                : sort.defaultDirection();
        Criteria criteria = new Criteria(trimToNull(request.query()), trimToNull(request.category()));

        long total = queries.count(filter.businessId(), criteria);
        long offset = (long) request.page() * request.size();
        List<Item> items = offset < total
                ? queries.page(filter, criteria, sort, direction, request.size(), offset)
                : List.of();
        int totalPages = (int) ((total + request.size() - 1) / request.size());

        return new ProductListResponse(
                filter.period(), filter.storeId(), criteria.search(), criteria.category(),
                sort.param(), direction.param(), request.page(), request.size(), total, totalPages, items);
    }

    public ProductCategoriesResponse categories() {
        return new ProductCategoriesResponse(queries.categories(reporting.currentBusiness().getId()));
    }

    public ProductDetailResponse detail(long productId, LocalDate from, LocalDate to, Long storeId) {
        ReportFilter filter = reporting.resolveFilter(from, to, storeId);
        Product product = findProduct(productId, filter.businessId());
        ReportFilter previous = filter.previous();
        Totals current = queries.totals(filter, productId);
        Totals prior = queries.totals(previous, productId);

        BigDecimal revenue = ReportCalculations.money(current.revenue());
        BigDecimal priorRevenue = ReportCalculations.money(prior.revenue());
        return new ProductDetailResponse(
                info(product),
                filter.period(),
                previous.period(),
                filter.storeId(),
                MetricValue.of(revenue, priorRevenue),
                MetricValue.of(current.units(), prior.units()),
                MetricValue.of(current.orders(), prior.orders()),
                MetricValue.of(averagePrice(revenue, current.units()), averagePrice(priorRevenue, prior.units())),
                queries.priceHistory(filter, productId));
    }

    public ProductSalesTrendResponse salesTrend(
            long productId, LocalDate from, LocalDate to, Long storeId, String granularityParam) {
        ReportFilter filter = reporting.resolveFilter(from, to, storeId);
        findProduct(productId, filter.businessId());
        Granularity granularity = reporting.resolveGranularity(granularityParam, filter);

        Map<LocalDate, TrendBucket> byStart = queries.trend(filter, productId, granularity).stream()
                .collect(Collectors.toMap(TrendBucket::bucketStart, Function.identity()));
        List<Point> points = ReportCalculations.buckets(filter.from(), filter.to(), granularity).stream()
                .map(b -> {
                    TrendBucket row = byStart.get(b.start());
                    BigDecimal revenue = ReportCalculations.money(row == null ? BigDecimal.ZERO : row.revenue());
                    long units = row == null ? 0 : row.units();
                    long orders = row == null ? 0 : row.orders();
                    return new Point(b.start(), revenue, units, orders, averagePrice(revenue, units),
                            b.daysCovered(), b.bucketDays(), b.complete());
                })
                .toList();
        return new ProductSalesTrendResponse(productId, filter.period(), filter.storeId(), granularity, points);
    }

    private Product findProduct(long productId, long businessId) {
        return products.findByIdAndBusinessId(productId, businessId)
                .orElseThrow(() -> ApiException.notFound("Product %d was not found.".formatted(productId)));
    }

    private static ProductInfo info(Product p) {
        return new ProductInfo(p.getId(), p.getSku(), p.getName(), p.getCategory(), p.getListPrice());
    }

    /** Average price actually charged, or {@code null} when nothing sold (a zero price would be misleading). */
    private static BigDecimal averagePrice(BigDecimal revenue, long units) {
        return units == 0 ? null : ReportCalculations.average(revenue, units);
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static <T> T parse(Supplier<T> parser) {
        try {
            return parser.get();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }
}
