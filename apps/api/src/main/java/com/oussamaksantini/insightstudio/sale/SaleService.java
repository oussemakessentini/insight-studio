package com.oussamaksantini.insightstudio.sale;

import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.product.Product;
import com.oussamaksantini.insightstudio.product.ProductRepository;
import com.oussamaksantini.insightstudio.reporting.ReportCalculations;
import com.oussamaksantini.insightstudio.reporting.ReportFilter;
import com.oussamaksantini.insightstudio.reporting.ReportingContext;
import com.oussamaksantini.insightstudio.sale.SaleQueries.Criteria;
import com.oussamaksantini.insightstudio.sale.SaleQueries.Header;
import com.oussamaksantini.insightstudio.sale.dto.SaleDetailResponse;
import com.oussamaksantini.insightstudio.sale.dto.SaleDetailResponse.Line;
import com.oussamaksantini.insightstudio.sale.dto.SaleListResponse;
import com.oussamaksantini.insightstudio.sale.dto.SaleListResponse.Item;
import com.oussamaksantini.insightstudio.sale.dto.SaleListResponse.ProductRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class SaleService {

    private final ReportingContext reporting;
    private final ProductRepository products;
    private final SaleQueries queries;

    SaleService(ReportingContext reporting, ProductRepository products, SaleQueries queries) {
        this.reporting = reporting;
        this.products = products;
        this.queries = queries;
    }

    public record ListRequest(
            LocalDate from, LocalDate to, Long storeId, String query, Long productId, String sort, int page, int size) {
    }

    public SaleListResponse list(ListRequest request) {
        ReportFilter filter = reporting.resolveFilter(request.from(), request.to(), request.storeId());
        SaleSort sort = parseSort(request.sort());
        ProductRef product = request.productId() == null ? null : productRef(request.productId(), filter.businessId());
        String search = StringUtils.hasText(request.query()) ? request.query().trim() : null;
        Criteria criteria = new Criteria(search, request.productId());

        long total = queries.count(filter, criteria);
        long offset = (long) request.page() * request.size();
        List<Item> items = offset < total ? queries.page(filter, criteria, sort, request.size(), offset) : List.of();
        int totalPages = (int) ((total + request.size() - 1) / request.size());

        return new SaleListResponse(filter.period(), filter.storeId(), search, product, sort.param(),
                request.page(), request.size(), total, totalPages, items);
    }

    public SaleDetailResponse detail(long saleId) {
        Business business = reporting.currentBusiness();
        Header header = queries.header(saleId, business.getId())
                .orElseThrow(() -> ApiException.notFound("Sale %d was not found.".formatted(saleId)));
        List<Line> lines = queries.lines(saleId);
        long units = lines.stream().mapToLong(Line::quantity).sum();
        BigDecimal total = ReportCalculations.money(lines.stream()
                .map(Line::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return new SaleDetailResponse(header.saleId(), header.receiptNumber(), header.soldAt(), header.store(),
                lines.size(), units, total, lines);
    }

    private ProductRef productRef(long productId, long businessId) {
        Product product = products.findByIdAndBusinessId(productId, businessId)
                .orElseThrow(() -> ApiException.notFound("Product %d was not found.".formatted(productId)));
        return new ProductRef(product.getId(), product.getSku(), product.getName());
    }

    private static SaleSort parseSort(String value) {
        if (!StringUtils.hasText(value)) {
            return SaleSort.NEWEST;
        }
        try {
            return SaleSort.fromParam(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }
}
