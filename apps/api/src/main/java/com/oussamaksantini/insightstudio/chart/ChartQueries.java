package com.oussamaksantini.insightstudio.chart;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Saved charts and their revisions (Flyway V14, plain JDBC). Every statement names the business, so a
 * chart is only ever read or changed through the business it belongs to; revisions are reached only
 * through their chart.
 */
@Repository
class ChartQueries {

    private static final String SELECT = """
            SELECT c.id, c.title, c.current_revision, r.definition::text AS definition,
                   creator.display_name AS created_by, updater.display_name AS updated_by,
                   c.created_at, c.updated_at
            FROM chart_definitions c
            JOIN chart_definition_revisions r
              ON r.chart_id = c.id AND r.business_id = c.business_id AND r.revision = c.current_revision
            JOIN users creator ON creator.id = c.created_by
            JOIN users updater ON updater.id = c.updated_by
            """;

    private final NamedParameterJdbcTemplate jdbc;

    ChartQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A chart with its current revision's definition (JSON text) and the names the API shows. */
    record ChartRow(
            long id,
            String title,
            int revision,
            String definition,
            String createdBy,
            String updatedBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** A revision; {@code definition} is {@code null} in lists. */
    record RevisionRow(int revision, String definition, String createdBy, Instant createdAt) {
    }

    /** Every chart of the business, by title ignoring case. */
    List<ChartRow> list(long businessId) {
        return jdbc.query(SELECT + """
                WHERE c.business_id = :businessId
                ORDER BY lower(c.title), c.id
                """, Map.of("businessId", businessId), (rs, i) -> row(rs));
    }

    Optional<ChartRow> find(long businessId, long id) {
        return jdbc.query(SELECT + "WHERE c.business_id = :businessId AND c.id = :id",
                Map.of("businessId", businessId, "id", id), (rs, i) -> row(rs)).stream().findFirst();
    }

    /**
     * Serializes chart creation per business (the 200-chart limit): locks the business row until the
     * transaction ends. {@code FOR NO KEY UPDATE} does not block inserts that reference the business.
     */
    void lockBusiness(long businessId) {
        jdbc.queryForList("SELECT id FROM businesses WHERE id = :businessId FOR NO KEY UPDATE",
                Map.of("businessId", businessId), Long.class);
    }

    int count(long businessId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM chart_definitions WHERE business_id = :businessId",
                Map.of("businessId", businessId), Integer.class);
        return count == null ? 0 : count;
    }

    /** Whether another chart of the business already has this title, ignoring case. */
    boolean titleTaken(long businessId, String title, Long exceptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM chart_definitions
                               WHERE business_id = :businessId AND lower(title) = lower(:title)
                                 AND (CAST(:exceptId AS BIGINT) IS NULL OR id <> :exceptId))
                """, new MapSqlParameterSource()
                        .addValue("businessId", businessId)
                        .addValue("title", title)
                        .addValue("exceptId", exceptId), Boolean.class));
    }

    /** Creates a chart with revision 1. */
    long insert(long businessId, String title, int schemaVersion, String definition, long userId) {
        Long id = jdbc.queryForObject("""
                INSERT INTO chart_definitions (business_id, title, current_revision, created_by, updated_by)
                VALUES (:businessId, :title, 1, :userId, :userId)
                RETURNING id
                """, new MapSqlParameterSource()
                        .addValue("businessId", businessId)
                        .addValue("title", title)
                        .addValue("userId", userId), Long.class);
        insertRevision(businessId, id, 1, schemaVersion, definition, userId);
        return id;
    }

    /**
     * Moves the chart to the next revision if its current one is still {@code expectedRevision}.
     *
     * @return the new revision, or empty when the chart is not at {@code expectedRevision} (or is not
     *     the business's)
     */
    Optional<Integer> advance(long businessId, long id, int expectedRevision, String title, long userId) {
        return jdbc.queryForList("""
                UPDATE chart_definitions
                SET title = :title, current_revision = current_revision + 1, updated_by = :userId, updated_at = now()
                WHERE business_id = :businessId AND id = :id AND current_revision = :expected
                RETURNING current_revision
                """, new MapSqlParameterSource()
                        .addValue("businessId", businessId)
                        .addValue("id", id)
                        .addValue("expected", expectedRevision)
                        .addValue("title", title)
                        .addValue("userId", userId), Integer.class).stream().findFirst();
    }

    void insertRevision(long businessId, long chartId, int revision, int schemaVersion, String definition, long userId) {
        jdbc.update("""
                INSERT INTO chart_definition_revisions
                    (chart_id, business_id, revision, schema_version, definition, created_by)
                VALUES (:chartId, :businessId, :revision, :schemaVersion, CAST(:definition AS jsonb), :userId)
                """, new MapSqlParameterSource()
                .addValue("chartId", chartId)
                .addValue("businessId", businessId)
                .addValue("revision", revision)
                .addValue("schemaVersion", schemaVersion)
                .addValue("definition", definition)
                .addValue("userId", userId));
    }

    /** Deletes the chart and (by the foreign key's cascade) its revisions; {@code false} when not found. */
    boolean delete(long businessId, long id) {
        return jdbc.update("DELETE FROM chart_definitions WHERE business_id = :businessId AND id = :id",
                Map.of("businessId", businessId, "id", id)) == 1;
    }

    /** The chart's revisions, newest first, without definitions. */
    List<RevisionRow> revisions(long businessId, long chartId) {
        return jdbc.query("""
                SELECT r.revision, NULL AS definition, u.display_name AS created_by, r.created_at
                FROM chart_definition_revisions r
                JOIN users u ON u.id = r.created_by
                WHERE r.business_id = :businessId AND r.chart_id = :chartId
                ORDER BY r.revision DESC
                """, Map.of("businessId", businessId, "chartId", chartId), (rs, i) -> revision(rs));
    }

    Optional<RevisionRow> revision(long businessId, long chartId, int revision) {
        return jdbc.query("""
                SELECT r.revision, r.definition::text AS definition, u.display_name AS created_by, r.created_at
                FROM chart_definition_revisions r
                JOIN users u ON u.id = r.created_by
                WHERE r.business_id = :businessId AND r.chart_id = :chartId AND r.revision = :revision
                """, Map.of("businessId", businessId, "chartId", chartId, "revision", revision),
                (rs, i) -> revision(rs)).stream().findFirst();
    }

    private static ChartRow row(ResultSet rs) throws SQLException {
        return new ChartRow(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getInt("current_revision"),
                rs.getString("definition"),
                rs.getString("created_by"),
                rs.getString("updated_by"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static RevisionRow revision(ResultSet rs) throws SQLException {
        return new RevisionRow(
                rs.getInt("revision"),
                rs.getString("definition"),
                rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
