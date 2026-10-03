package com.oussamaksantini.insightstudio.customdashboard;

import com.oussamaksantini.insightstudio.customdashboard.dto.DashboardReferenceResponse;
import com.oussamaksantini.insightstudio.customdashboard.dto.DashboardWidgetResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Custom dashboards, their revisions and chart references (Flyway V15, plain JDBC). Every statement
 * names the business, so a dashboard (or a chart it places) is only ever read or changed through the
 * business it belongs to; revisions and references are reached only through their dashboard.
 */
@Repository
class CustomDashboardQueries {

    private static final String SELECT = """
            SELECT d.id, d.name, d.current_revision, r.layout::text AS layout,
                   creator.display_name AS created_by, updater.display_name AS updated_by,
                   d.created_at, d.updated_at
            FROM dashboards d
            JOIN dashboard_revisions r
              ON r.dashboard_id = d.id AND r.business_id = d.business_id AND r.revision = d.current_revision
            JOIN users creator ON creator.id = d.created_by
            JOIN users updater ON updater.id = d.updated_by
            """;

    /** Widgets of a layout whose chart is not (any more) a chart of the dashboard's business. */
    private static final String MISSING_COUNT = """
            (SELECT COUNT(*) FROM jsonb_array_elements(r.layout -> 'widgets') AS w
             WHERE NOT EXISTS (SELECT 1 FROM chart_definitions c
                               WHERE c.business_id = r.business_id AND c.id = (w ->> 'chartId')::bigint))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    CustomDashboardQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A dashboard with its current revision's layout (JSON text) and the names the API shows. */
    record DashboardRow(
            long id,
            String name,
            int revision,
            String layout,
            String createdBy,
            String updatedBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    record SummaryRow(
            long id,
            String name,
            int revision,
            int widgetCount,
            int missingCount,
            String updatedBy,
            Instant updatedAt) {
    }

    /** A revision; {@code layout} is {@code null} in lists. */
    record RevisionRow(int revision, String name, int widgetCount, String layout, String createdBy, Instant createdAt) {
    }

    /** Every dashboard of the business, by name ignoring case. */
    List<SummaryRow> list(long businessId) {
        return jdbc.query("""
                SELECT d.id, d.name, d.current_revision, jsonb_array_length(r.layout -> 'widgets') AS widget_count,
                """ + MISSING_COUNT + """
                       AS missing_count, updater.display_name AS updated_by, d.updated_at
                FROM dashboards d
                JOIN dashboard_revisions r
                  ON r.dashboard_id = d.id AND r.business_id = d.business_id AND r.revision = d.current_revision
                JOIN users updater ON updater.id = d.updated_by
                WHERE d.business_id = :businessId
                ORDER BY lower(d.name), d.id
                """, Map.of("businessId", businessId), (rs, i) -> new SummaryRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getInt("current_revision"),
                        rs.getInt("widget_count"),
                        rs.getInt("missing_count"),
                        rs.getString("updated_by"),
                        instant(rs, "updated_at")));
    }

    Optional<DashboardRow> find(long businessId, long id) {
        return jdbc.query(SELECT + "WHERE d.business_id = :businessId AND d.id = :id",
                Map.of("businessId", businessId, "id", id), (rs, i) -> row(rs)).stream().findFirst();
    }

    /**
     * Serializes dashboard creation per business (the 50-dashboard limit): locks the business row until
     * the transaction ends. {@code FOR NO KEY UPDATE} does not block inserts that reference the business.
     */
    void lockBusiness(long businessId) {
        jdbc.queryForList("SELECT id FROM businesses WHERE id = :businessId FOR NO KEY UPDATE",
                Map.of("businessId", businessId), Long.class);
    }

    int count(long businessId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM dashboards WHERE business_id = :businessId",
                Map.of("businessId", businessId), Integer.class);
        return count == null ? 0 : count;
    }

    /** Whether another dashboard of the business already has this name, ignoring case. */
    boolean nameTaken(long businessId, String name, Long exceptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM dashboards
                               WHERE business_id = :businessId AND lower(name) = lower(:name)
                                 AND (CAST(:exceptId AS BIGINT) IS NULL OR id <> :exceptId))
                """, new MapSqlParameterSource()
                        .addValue("businessId", businessId)
                        .addValue("name", name)
                        .addValue("exceptId", exceptId), Boolean.class));
    }

    /** Creates a dashboard at revision 1 (its revision and references are written by the caller). */
    long insert(long businessId, String name, long userId) {
        return jdbc.queryForObject("""
                INSERT INTO dashboards (business_id, name, current_revision, created_by, updated_by)
                VALUES (:businessId, :name, 1, :userId, :userId)
                RETURNING id
                """, new MapSqlParameterSource()
                        .addValue("businessId", businessId)
                        .addValue("name", name)
                        .addValue("userId", userId), Long.class);
    }

    /**
     * Moves the dashboard to the next revision if its current one is still {@code expectedRevision}.
     *
     * @return the new revision, or empty when the dashboard is not at {@code expectedRevision} (or is
     *     not the business's)
     */
    Optional<Integer> advance(long businessId, long id, int expectedRevision, String name, long userId) {
        return jdbc.queryForList("""
                UPDATE dashboards
                SET name = :name, current_revision = current_revision + 1, updated_by = :userId, updated_at = now()
                WHERE business_id = :businessId AND id = :id AND current_revision = :expected
                RETURNING current_revision
                """, new MapSqlParameterSource()
                        .addValue("businessId", businessId)
                        .addValue("id", id)
                        .addValue("expected", expectedRevision)
                        .addValue("name", name)
                        .addValue("userId", userId), Integer.class).stream().findFirst();
    }

    void insertRevision(long businessId, long dashboardId, int revision, String name, int schemaVersion, String layout,
            long userId) {
        jdbc.update("""
                INSERT INTO dashboard_revisions
                    (dashboard_id, business_id, revision, name, schema_version, layout, created_by)
                VALUES (:dashboardId, :businessId, :revision, :name, :schemaVersion, CAST(:layout AS jsonb), :userId)
                """, new MapSqlParameterSource()
                .addValue("dashboardId", dashboardId)
                .addValue("businessId", businessId)
                .addValue("revision", revision)
                .addValue("name", name)
                .addValue("schemaVersion", schemaVersion)
                .addValue("layout", layout)
                .addValue("userId", userId));
    }

    /**
     * Replaces the dashboard's chart references with {@code chartIds} (its current layout's charts).
     * The composite foreign key refuses a chart of another business.
     */
    void replaceChartRefs(long businessId, long dashboardId, Collection<Long> chartIds) {
        jdbc.update("DELETE FROM dashboard_chart_refs WHERE business_id = :businessId AND dashboard_id = :dashboardId",
                Map.of("businessId", businessId, "dashboardId", dashboardId));
        jdbc.batchUpdate("""
                INSERT INTO dashboard_chart_refs (dashboard_id, business_id, chart_id)
                VALUES (:dashboardId, :businessId, :chartId)
                """, chartIds.stream().map(chartId -> new MapSqlParameterSource()
                        .addValue("dashboardId", dashboardId)
                        .addValue("businessId", businessId)
                        .addValue("chartId", chartId)).toArray(SqlParameterSource[]::new));
    }

    /** Deletes the dashboard and (by the foreign keys' cascade) its revisions and references. */
    boolean delete(long businessId, long id) {
        return jdbc.update("DELETE FROM dashboards WHERE business_id = :businessId AND id = :id",
                Map.of("businessId", businessId, "id", id)) == 1;
    }

    /** The dashboard's revisions, newest first, without layouts. */
    List<RevisionRow> revisions(long businessId, long dashboardId) {
        return jdbc.query("""
                SELECT r.revision, r.name, jsonb_array_length(r.layout -> 'widgets') AS widget_count, NULL AS layout,
                       u.display_name AS created_by, r.created_at
                FROM dashboard_revisions r
                JOIN users u ON u.id = r.created_by
                WHERE r.business_id = :businessId AND r.dashboard_id = :dashboardId
                ORDER BY r.revision DESC
                """, Map.of("businessId", businessId, "dashboardId", dashboardId), (rs, i) -> revision(rs));
    }

    Optional<RevisionRow> revision(long businessId, long dashboardId, int revision) {
        return jdbc.query("""
                SELECT r.revision, r.name, jsonb_array_length(r.layout -> 'widgets') AS widget_count,
                       r.layout::text AS layout, u.display_name AS created_by, r.created_at
                FROM dashboard_revisions r
                JOIN users u ON u.id = r.created_by
                WHERE r.business_id = :businessId AND r.dashboard_id = :dashboardId AND r.revision = :revision
                """, Map.of("businessId", businessId, "dashboardId", dashboardId, "revision", revision),
                (rs, i) -> revision(rs)).stream().findFirst();
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

    /** The current title, visualization and revision of the given charts of the business: id -> chart. */
    Map<Long, DashboardWidgetResponse.Chart> chartSummaries(long businessId, Collection<Long> ids) {
        Map<Long, DashboardWidgetResponse.Chart> charts = new HashMap<>();
        if (ids.isEmpty()) {
            return charts;
        }
        jdbc.query("""
                SELECT c.id, c.title, r.definition ->> 'visualization' AS visualization, c.current_revision
                FROM chart_definitions c
                JOIN chart_definition_revisions r
                  ON r.chart_id = c.id AND r.business_id = c.business_id AND r.revision = c.current_revision
                WHERE c.business_id = :businessId AND c.id IN (:ids)
                """, new MapSqlParameterSource("businessId", businessId).addValue("ids", ids), rs -> {
                    long id = rs.getLong("id");
                    charts.put(id, new DashboardWidgetResponse.Chart(id, rs.getString("title"),
                            rs.getString("visualization"), rs.getInt("current_revision")));
                });
        return charts;
    }

    boolean chartExists(long businessId, long chartId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM chart_definitions WHERE business_id = :businessId AND id = :id)
                """, Map.of("businessId", businessId, "id", chartId), Boolean.class));
    }

    /** The dashboards of the business whose current layout places the chart, by name ignoring case. */
    List<DashboardReferenceResponse> dashboardsUsing(long businessId, long chartId) {
        return jdbc.query("""
                SELECT d.id, d.name
                FROM dashboard_chart_refs ref
                JOIN dashboards d ON d.id = ref.dashboard_id AND d.business_id = ref.business_id
                WHERE ref.business_id = :businessId AND ref.chart_id = :chartId
                ORDER BY lower(d.name), d.id
                """, Map.of("businessId", businessId, "chartId", chartId),
                (rs, i) -> new DashboardReferenceResponse(rs.getLong("id"), rs.getString("name")));
    }

    private static DashboardRow row(ResultSet rs) throws SQLException {
        return new DashboardRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getInt("current_revision"),
                rs.getString("layout"),
                rs.getString("created_by"),
                rs.getString("updated_by"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static RevisionRow revision(ResultSet rs) throws SQLException {
        return new RevisionRow(
                rs.getInt("revision"),
                rs.getString("name"),
                rs.getInt("widget_count"),
                rs.getString("layout"),
                rs.getString("created_by"),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
