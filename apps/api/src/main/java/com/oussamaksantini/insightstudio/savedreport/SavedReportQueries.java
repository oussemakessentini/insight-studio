package com.oussamaksantini.insightstudio.savedreport;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Saved report definitions (plain JDBC). Every statement names the business, so a definition is
 * only ever read or changed through the business it belongs to.
 */
@Repository
class SavedReportQueries {

    private static final String SELECT = """
            SELECT r.id, r.name, r.kind, r.date_from, r.date_to, r.relative_preset, r.store_id,
                   st.name AS store_name, st.code AS store_code, u.display_name AS created_by,
                   r.created_at, r.updated_at
            FROM saved_reports r
            JOIN users u ON u.id = r.created_by
            LEFT JOIN stores st ON st.id = r.store_id AND st.business_id = r.business_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    SavedReportQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A stored definition with the names the API shows next to it. */
    record SavedReportRow(
            long id,
            String name,
            ReportKind kind,
            SavedRange range,
            Long storeId,
            String storeName,
            String storeCode,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** Every definition of the business, by name ignoring case. */
    List<SavedReportRow> list(long businessId) {
        return jdbc.query(SELECT + """
                WHERE r.business_id = :businessId
                ORDER BY lower(r.name), r.id
                """, Map.of("businessId", businessId), (rs, i) -> row(rs));
    }

    Optional<SavedReportRow> find(long businessId, long id) {
        return jdbc.query(SELECT + "WHERE r.business_id = :businessId AND r.id = :id",
                Map.of("businessId", businessId, "id", id), (rs, i) -> row(rs)).stream().findFirst();
    }

    /** Whether another definition of the business already has this name, ignoring case. */
    boolean nameTaken(long businessId, String name, Long exceptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM saved_reports
                               WHERE business_id = :businessId AND lower(name) = lower(:name)
                                 AND (CAST(:exceptId AS BIGINT) IS NULL OR id <> :exceptId))
                """, new MapSqlParameterSource()
                        .addValue("businessId", businessId)
                        .addValue("name", name)
                        .addValue("exceptId", exceptId), Boolean.class));
    }

    long insert(long businessId, String name, ReportKind kind, SavedRange range, Long storeId, long userId) {
        return jdbc.queryForObject("""
                INSERT INTO saved_reports
                    (business_id, name, kind, range_type, date_from, date_to, relative_preset, store_id, created_by)
                VALUES (:businessId, :name, :kind, :rangeType, :from, :to, :preset, :storeId, :userId)
                RETURNING id
                """, values(businessId, name, kind, range, storeId).addValue("userId", userId), Long.class);
    }

    /** Returns {@code false} when the business has no such definition. */
    boolean update(long businessId, long id, String name, ReportKind kind, SavedRange range, Long storeId) {
        return jdbc.update("""
                UPDATE saved_reports
                SET name = :name, kind = :kind, range_type = :rangeType, date_from = :from, date_to = :to,
                    relative_preset = :preset, store_id = :storeId, updated_at = now()
                WHERE business_id = :businessId AND id = :id
                """, values(businessId, name, kind, range, storeId).addValue("id", id)) == 1;
    }

    /** Returns {@code false} when the business has no such definition. */
    boolean delete(long businessId, long id) {
        return jdbc.update("DELETE FROM saved_reports WHERE business_id = :businessId AND id = :id",
                Map.of("businessId", businessId, "id", id)) == 1;
    }

    private static MapSqlParameterSource values(
            long businessId, String name, ReportKind kind, SavedRange range, Long storeId) {
        return new MapSqlParameterSource()
                .addValue("businessId", businessId)
                .addValue("name", name)
                .addValue("kind", kind.code())
                .addValue("rangeType", range.type())
                .addValue("from", range.from())
                .addValue("to", range.to())
                .addValue("preset", range.isRelative() ? range.preset().code() : null)
                .addValue("storeId", storeId);
    }

    private static SavedReportRow row(ResultSet rs) throws SQLException {
        String preset = rs.getString("relative_preset");
        SavedRange range = preset != null
                ? SavedRange.relative(RelativePreset.fromCode(preset).orElseThrow())
                : SavedRange.fixed(rs.getObject("date_from", LocalDate.class), rs.getObject("date_to", LocalDate.class));
        return new SavedReportRow(
                rs.getLong("id"),
                rs.getString("name"),
                ReportKind.fromCode(rs.getString("kind")).orElseThrow(),
                range,
                rs.getObject("store_id", Long.class),
                rs.getString("store_name"),
                rs.getString("store_code"),
                rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
