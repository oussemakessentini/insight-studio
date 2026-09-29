package com.oussamaksantini.insightstudio.demo;

import com.oussamaksantini.insightstudio.business.Business;
import com.oussamaksantini.insightstudio.business.BusinessRepository;
import com.oussamaksantini.insightstudio.demo.DemoCatalog.ProductSpec;
import com.oussamaksantini.insightstudio.demo.DemoCatalog.StoreSpec;
import com.oussamaksantini.insightstudio.product.Product;
import com.oussamaksantini.insightstudio.product.ProductRepository;
import com.oussamaksantini.insightstudio.store.Store;
import com.oussamaksantini.insightstudio.store.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the fictional demo business when the {@code demo} profile is active.
 *
 * <p>The data is deterministic: a fixed random seed and fixed dates produce the same stores,
 * products and sales on every run. Seeding is skipped when the demo business already exists, so
 * restarting the API never duplicates data. It never runs without the {@code demo} profile.
 */
@Component
@Profile("demo")
class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    static final long RANDOM_SEED = 20260301L;
    private static final DateTimeFormatter RECEIPT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int BATCH_SIZE = 1000;

    private final BusinessRepository businesses;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    DemoDataSeeder(
            BusinessRepository businesses,
            StoreRepository stores,
            ProductRepository products,
            JdbcTemplate jdbc,
            TransactionTemplate transaction) {
        this.businesses = businesses;
        this.stores = stores;
        this.products = products;
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** @return {@code true} when data was inserted, {@code false} when the demo business already existed */
    boolean seed() {
        if (businesses.findBySlug(DemoCatalog.BUSINESS_SLUG).isPresent()) {
            log.info("Demo data already present ('{}'); skipping seed.", DemoCatalog.BUSINESS_SLUG);
            return false;
        }
        long started = System.currentTimeMillis();
        SeedResult result = transaction.execute(status -> insertAll());
        log.info("Seeded demo data for '{}': {} stores, {} products, {} sales, {} sale items in {} ms.",
                DemoCatalog.BUSINESS_NAME, DemoCatalog.STORES.size(), DemoCatalog.PRODUCTS.size(),
                result.sales(), result.items(), System.currentTimeMillis() - started);
        return true;
    }

    private record SeedResult(int sales, int items) {
    }

    private record PlannedItem(long productId, int quantity, BigDecimal unitPrice) {
    }

    private record PlannedSale(long storeId, String receiptNumber, OffsetDateTime soldAt, List<PlannedItem> items) {
    }

    private SeedResult insertAll() {
        Business business = businesses.save(new Business(
                DemoCatalog.BUSINESS_NAME, DemoCatalog.BUSINESS_SLUG, DemoCatalog.CURRENCY, DemoCatalog.TIME_ZONE));

        Map<String, Store> storesByCode = new HashMap<>();
        for (StoreSpec spec : DemoCatalog.STORES) {
            storesByCode.put(spec.code(), stores.save(new Store(business, spec.code(), spec.name(), spec.city())));
        }
        List<Long> productIds = new ArrayList<>();
        for (ProductSpec spec : DemoCatalog.PRODUCTS) {
            productIds.add(products.save(new Product(
                    business, spec.sku(), spec.name(), spec.category(), spec.currentPrice())).getId());
        }
        stores.flush();
        products.flush();

        List<PlannedSale> sales = planSales(business.zoneId(), storesByCode, productIds);
        insertSales(sales);
        Map<String, Long> saleIds = loadSaleIds(business.getId());
        int itemCount = insertItems(sales, saleIds);
        return new SeedResult(sales.size(), itemCount);
    }

    private List<PlannedSale> planSales(ZoneId zone, Map<String, Store> storesByCode, List<Long> productIds) {
        Random random = new Random(RANDOM_SEED);
        List<PlannedSale> planned = new ArrayList<>();
        for (LocalDate day = DemoCatalog.FIRST_SALE_DAY; !day.isAfter(DemoCatalog.LAST_SALE_DAY); day = day.plusDays(1)) {
            double[] weights = new double[DemoCatalog.PRODUCTS.size()];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = DemoCatalog.demandWeight(DemoCatalog.PRODUCTS.get(i), day);
            }
            for (StoreSpec storeSpec : DemoCatalog.STORES) {
                long storeId = storesByCode.get(storeSpec.code()).getId();
                int count = dailySaleCount(storeSpec, day, random);
                List<LocalTime> times = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    times.add(randomTime(storeSpec, random));
                }
                times.sort(null);
                for (int i = 0; i < count; i++) {
                    String receipt = "%s-%s-%03d".formatted(storeSpec.code(), day.format(RECEIPT_DATE), i + 1);
                    OffsetDateTime soldAt = day.atTime(times.get(i)).atZone(zone)
                            .toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
                    planned.add(new PlannedSale(storeId, receipt, soldAt, planItems(day, weights, productIds, random)));
                }
            }
        }
        return planned;
    }

    private static int dailySaleCount(StoreSpec store, LocalDate day, Random random) {
        double weekday = switch (day.getDayOfWeek()) {
            case SATURDAY -> store.physical() ? 1.6 : 1.2;
            case SUNDAY -> store.physical() ? 1.35 : 1.25;
            case FRIDAY -> 1.15;
            case MONDAY -> store.physical() ? 0.8 : 1.05;
            default -> 1.0;
        };
        // Gentle growth over the six months.
        double trend = 1.0 + 0.035 * (day.getMonthValue() - DemoCatalog.FIRST_SALE_DAY.getMonthValue());
        double expected = store.expectedDailySales() * weekday * trend;
        return (int) Math.max(0, Math.round(expected * (0.75 + random.nextDouble() * 0.5)));
    }

    private static LocalTime randomTime(StoreSpec store, Random random) {
        // Physical stores trade 10:00-20:00; online orders arrive 07:00-23:59.
        int openMinute = store.physical() ? 10 * 60 : 7 * 60;
        int closeMinute = store.physical() ? 20 * 60 : 24 * 60;
        int minute = openMinute + random.nextInt(closeMinute - openMinute);
        return LocalTime.of(minute / 60, minute % 60, random.nextInt(60));
    }

    private static List<PlannedItem> planItems(LocalDate day, double[] weights, List<Long> productIds, Random random) {
        double roll = random.nextDouble();
        int lines = roll < 0.50 ? 1 : roll < 0.82 ? 2 : roll < 0.95 ? 3 : 4;
        double[] remaining = weights.clone();
        List<PlannedItem> items = new ArrayList<>(lines);
        for (int i = 0; i < lines; i++) {
            int index = weightedIndex(remaining, random);
            remaining[index] = 0; // at most one line per product
            ProductSpec spec = DemoCatalog.PRODUCTS.get(index);
            double quantityRoll = random.nextDouble();
            int quantity = quantityRoll < 0.84 ? 1 : quantityRoll < 0.97 ? 2 : 3;
            items.add(new PlannedItem(productIds.get(index), quantity, spec.priceOn(day)));
        }
        return items;
    }

    private static int weightedIndex(double[] weights, Random random) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        double target = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            target -= weights[i];
            if (target < 0 && weights[i] > 0) {
                return i;
            }
        }
        for (int i = weights.length - 1; i >= 0; i--) {
            if (weights[i] > 0) {
                return i;
            }
        }
        throw new IllegalStateException("No products left to choose from");
    }

    private void insertSales(List<PlannedSale> sales) {
        List<Object[]> rows = sales.stream()
                .map(s -> new Object[] {s.storeId(), s.receiptNumber(), s.soldAt()})
                .toList();
        batch("INSERT INTO sales (store_id, receipt_number, sold_at) VALUES (?, ?, ?)", rows);
    }

    private Map<String, Long> loadSaleIds(long businessId) {
        Map<String, Long> ids = new HashMap<>();
        jdbc.query("""
                SELECT s.id, s.store_id, s.receipt_number
                FROM sales s JOIN stores st ON st.id = s.store_id
                WHERE st.business_id = ?
                """,
                rs -> {
                    ids.put(saleKey(rs.getLong("store_id"), rs.getString("receipt_number")), rs.getLong("id"));
                },
                businessId);
        return ids;
    }

    private int insertItems(List<PlannedSale> sales, Map<String, Long> saleIds) {
        List<Object[]> rows = new ArrayList<>();
        for (PlannedSale sale : sales) {
            long saleId = saleIds.get(saleKey(sale.storeId(), sale.receiptNumber()));
            for (PlannedItem item : sale.items()) {
                rows.add(new Object[] {saleId, item.productId(), item.quantity(), item.unitPrice()});
            }
        }
        batch("INSERT INTO sale_items (sale_id, product_id, quantity, unit_price) VALUES (?, ?, ?, ?)", rows);
        return rows.size();
    }

    private void batch(String sql, List<Object[]> rows) {
        for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
            jdbc.batchUpdate(sql, rows.subList(from, Math.min(from + BATCH_SIZE, rows.size())));
        }
    }

    private static String saleKey(long storeId, String receiptNumber) {
        return storeId + "/" + receiptNumber;
    }
}
