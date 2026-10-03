package com.oussamaksantini.insightstudio.chart;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The business's stores, products and categories as charts need them: filter options for the
 * catalogue, filter validation, and group labels. Every statement names the business, so another
 * business's ids are never found (and never confirmed).
 */
@Repository
class ChartLookups {

    private final NamedParameterJdbcTemplate jdbc;

    ChartLookups(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every store of the business by name: id -> name. */
    Map<Long, String> stores(long businessId) {
        Map<Long, String> stores = new LinkedHashMap<>();
        jdbc.query("SELECT id, name FROM stores WHERE business_id = :businessId ORDER BY name, id",
                Map.of("businessId", businessId), rs -> {
                    stores.put(rs.getLong("id"), rs.getString("name"));
                });
        return stores;
    }

    /** Every category of the business's catalogue, by name. */
    List<String> categories(long businessId) {
        return jdbc.queryForList(
                "SELECT DISTINCT category FROM products WHERE business_id = :businessId ORDER BY category",
                Map.of("businessId", businessId), String.class);
    }

    /** The categories of the given products of the business. */
    List<String> categoriesOf(long businessId, Collection<Long> productIds) {
        return jdbc.queryForList("""
                SELECT DISTINCT category FROM products
                WHERE business_id = :businessId AND id IN (:ids)
                ORDER BY category
                """, new MapSqlParameterSource("businessId", businessId).addValue("ids", productIds), String.class);
    }

    /** Names of the given products of the business: id -> name. */
    Map<Long, String> productNames(long businessId, Collection<Long> productIds) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (productIds.isEmpty()) {
            return names;
        }
        jdbc.query("SELECT id, name FROM products WHERE business_id = :businessId AND id IN (:ids)",
                new MapSqlParameterSource("businessId", businessId).addValue("ids", productIds), rs -> {
                    names.put(rs.getLong("id"), rs.getString("name"));
                });
        return names;
    }

    /** Which of {@code ids} are stores of the business. */
    Set<Long> existingStores(long businessId, Collection<Long> ids) {
        return ids.isEmpty() ? Set.of() : new HashSet<>(jdbc.queryForList(
                "SELECT id FROM stores WHERE business_id = :businessId AND id IN (:ids)",
                new MapSqlParameterSource("businessId", businessId).addValue("ids", ids), Long.class));
    }

    /** Which of {@code ids} are products of the business. */
    Set<Long> existingProducts(long businessId, Collection<Long> ids) {
        return ids.isEmpty() ? Set.of() : new HashSet<>(jdbc.queryForList(
                "SELECT id FROM products WHERE business_id = :businessId AND id IN (:ids)",
                new MapSqlParameterSource("businessId", businessId).addValue("ids", ids), Long.class));
    }

    /** Which of {@code names} are categories of the business's catalogue (exact names). */
    Set<String> existingCategories(long businessId, Collection<String> names) {
        return names.isEmpty() ? Set.of() : new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT category FROM products WHERE business_id = :businessId AND category IN (:names)",
                new MapSqlParameterSource("businessId", businessId).addValue("names", names), String.class));
    }
}
