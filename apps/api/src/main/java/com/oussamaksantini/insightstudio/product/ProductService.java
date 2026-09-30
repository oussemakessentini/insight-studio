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
import com.oussamaksantini.insightstudio.tenancy.Role;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class ProductService {

    /** SKUs are matched exactly by CSV imports, so they are kept simple. */
    static final Pattern SKU = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$");
    static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");

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

    /** ADMIN+: adds a product to the current business's catalog; 409 when the SKU is taken there. */
    @Transactional
    public ProductInfo create(String sku, String name, String category, BigDecimal listPrice) {
        Business business = reporting.currentBusiness(Role.ADMIN);
        String cleanSku = sku == null ? "" : sku.strip();
        if (!SKU.matcher(cleanSku).matches()) {
            throw ApiException.badRequest(
                    "'sku' must be 1 to 50 letters, digits, '.', '_' or '-', starting with a letter or digit.");
        }
        String cleanName = text(name, "name", 200);
        String cleanCategory = text(category, "category", 100);
        if (listPrice == null || listPrice.signum() < 0 || listPrice.compareTo(MAX_PRICE) > 0
                || listPrice.stripTrailingZeros().scale() > 2) {
            throw ApiException.badRequest("'listPrice' must be a price of 0 or more with at most 2 decimals.");
        }
        BigDecimal price = listPrice.setScale(2);
        long id = queries.insertProduct(business.getId(), cleanSku, cleanName, cleanCategory, price)
                .orElseThrow(() -> ApiException.conflict("A product with SKU '%s' already exists.".formatted(cleanSku)));
        return new ProductInfo(id, cleanSku, cleanName, cleanCategory, price);
    }

    private static String text(String value, String field, int maxLength) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty()) {
            throw ApiException.badRequest("'%s' is required.".formatted(field));
        }
        if (clean.length() > maxLength || clean.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("'%s' must be at most %d characters of text.".formatted(field, maxLength));
        }
        return clean;
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
