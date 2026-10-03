package com.oussamaksantini.insightstudio.customdashboard;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Custom dashboards, their revisions and chart references (Flyway V15, plain JDBC). Every statement
 * names the business, so a dashboard (or a chart it places) is only ever read or changed through the
 * business it belongs to.
 */
@Repository
class CustomDashboardQueries {

    private final NamedParameterJdbcTemplate jdbc;

    CustomDashboardQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Which of {@code ids} are charts of the business. Writes call this before storing a layout: the
     * rows are locked ({@code FOR KEY SHARE}) until the transaction ends, so a chart cannot be deleted
     * between this check and the reference rows that point at it.
     */
    Set<Long> chartsOfBusiness(long businessId, Collection<Long> ids) {
        return ids.isEmpty() ? Set.of() : new HashSet<>(jdbc.queryForList("""
                SELECT id FROM chart_definitions WHERE business_id = :businessId AND id IN (:ids)
                FOR KEY SHARE
                """, new MapSqlParameterSource("businessId", businessId).addValue("ids", ids), Long.class));
    }
}
