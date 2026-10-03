package com.oussamaksantini.insightstudio.accountdata;

import com.oussamaksantini.insightstudio.accountdata.AccountDataQueries.BusinessRow;
import com.oussamaksantini.insightstudio.audit.AuditQueries;
import com.oussamaksantini.insightstudio.audit.dto.AuditEventResponse;
import com.oussamaksantini.insightstudio.report.CsvWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a business's data as a ZIP (docs/account-management-contract.md §3): UTF-8 CSV files
 * (header row, RFC 4180, text cells guarded against formula injection by {@link CsvWriter}) and JSON
 * files. Runs inside the caller's (snapshot) transaction.
 *
 * <p>Never exported: password hashes, sessions, token hashes (invitations, resets, verifications),
 * email bodies, uploaded CSV contents and file hashes. Members are listed with their email: the
 * owner exporting already sees it on the members page.
 */
@Component
class BusinessExport {

    static final int FORMAT_VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /** Writes one CSV row from the current row of a result set. */
    @FunctionalInterface
    private interface RowWriter {
        void write(ResultSet rs, CsvWriter csv) throws SQLException;
    }

    private final AccountDataQueries queries;
    private final AuditQueries audit;

    BusinessExport(AccountDataQueries queries, AuditQueries audit) {
        this.queries = queries;
        this.audit = audit;
    }

    byte[] zip(BusinessRow business, Instant exportedAt) {
        long b = business.id();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("id", business.id());
            settings.put("name", business.name());
            settings.put("slug", business.slug());
            settings.put("currency", business.currency());
            settings.put("timeZone", business.timeZone());
            settings.put("createdAt", iso(business.createdAt()));
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("formatVersion", FORMAT_VERSION);
            meta.put("exportedAt", exportedAt.toString());
            meta.put("business", settings);
            json(zip, "business.json", meta);

            csv(zip, "members.csv", """
                    SELECT u.id, u.display_name, u.email, m.role, m.created_at
                    FROM memberships m JOIN users u ON u.id = m.user_id
                    WHERE m.business_id = :b ORDER BY m.created_at, u.id
                    """, b, new String[] {"user_id", "display_name", "email", "role", "member_since"},
                    (rs, csv) -> csv.number(rs.getLong("id")).text(rs.getString("display_name"))
                            .text(rs.getString("email")).literal(rs.getString("role")).literal(iso(rs, "created_at")));

            csv(zip, "invitations.csv", """
                    SELECT i.id, i.email, i.role, u.display_name AS invited_by, i.created_at, i.expires_at,
                           i.accepted_at, i.revoked_at,
                           CASE WHEN i.accepted_at IS NOT NULL THEN 'accepted'
                                WHEN i.revoked_at IS NOT NULL THEN 'revoked'
                                WHEN i.expires_at <= now() THEN 'expired' ELSE 'open' END AS status
                    FROM invitations i JOIN users u ON u.id = i.invited_by
                    WHERE i.business_id = :b ORDER BY i.id
                    """, b, new String[] {"id", "email", "role", "status", "invited_by", "created_at", "expires_at",
                            "accepted_at", "revoked_at"},
                    (rs, csv) -> csv.number(rs.getLong("id")).text(rs.getString("email")).literal(rs.getString("role"))
                            .literal(rs.getString("status")).text(rs.getString("invited_by"))
                            .literal(iso(rs, "created_at")).literal(iso(rs, "expires_at"))
                            .literal(iso(rs, "accepted_at")).literal(iso(rs, "revoked_at")));

            csv(zip, "stores.csv", "SELECT id, code, name, city, created_at FROM stores WHERE business_id = :b ORDER BY id",
                    b, new String[] {"id", "code", "name", "city", "created_at"},
                    (rs, csv) -> csv.number(rs.getLong("id")).text(rs.getString("code")).text(rs.getString("name"))
                            .text(rs.getString("city")).literal(iso(rs, "created_at")));

            csv(zip, "products.csv", """
                    SELECT id, sku, name, category, list_price, created_at FROM products WHERE business_id = :b ORDER BY id
                    """, b, new String[] {"id", "sku", "name", "category", "list_price", "created_at"},
                    (rs, csv) -> csv.number(rs.getLong("id")).text(rs.getString("sku")).text(rs.getString("name"))
                            .text(rs.getString("category")).number(rs.getBigDecimal("list_price"))
                            .literal(iso(rs, "created_at")));

            csv(zip, "sales.csv", """
                    SELECT s.id, s.store_id, st.code AS store_code, s.receipt_number, s.sold_at, s.import_batch_id, s.created_at
                    FROM sales s JOIN stores st ON st.id = s.store_id
                    WHERE s.business_id = :b ORDER BY s.id
                    """, b, new String[] {"id", "store_id", "store_code", "receipt_number", "sold_at", "import_id", "created_at"},
                    (rs, csv) -> {
                        csv.number(rs.getLong("id")).number(rs.getLong("store_id")).text(rs.getString("store_code"))
                                .text(rs.getString("receipt_number")).literal(iso(rs, "sold_at"));
                        long batch = rs.getLong("import_batch_id");
                        (rs.wasNull() ? csv.literal("") : csv.number(batch)).literal(iso(rs, "created_at"));
                    });

            csv(zip, "sale_items.csv", """
                    SELECT si.id, si.sale_id, si.product_id, p.sku, si.quantity, si.unit_price
                    FROM sale_items si JOIN products p ON p.id = si.product_id
                    WHERE si.business_id = :b ORDER BY si.id
                    """, b, new String[] {"id", "sale_id", "product_id", "sku", "quantity", "unit_price"},
                    (rs, csv) -> csv.number(rs.getLong("id")).number(rs.getLong("sale_id")).number(rs.getLong("product_id"))
                            .text(rs.getString("sku")).number(rs.getLong("quantity")).number(rs.getBigDecimal("unit_price")));

            csv(zip, "imports.csv", """
                    SELECT b.id, b.kind, b.mode, b.status, b.file_name, b.row_count, b.sale_count, b.line_count,
                           b.total_amount, b.created_count, b.updated_count, b.unchanged_count, b.error_count,
                           u.display_name AS imported_by, b.created_at
                    FROM import_batches b LEFT JOIN users u ON u.id = b.created_by
                    WHERE b.business_id = :b ORDER BY b.id
                    """, b, new String[] {"id", "kind", "mode", "status", "file_name", "rows", "sales", "line_items",
                            "total_amount", "created", "updated", "unchanged", "errors", "imported_by", "created_at"},
                    (rs, csv) -> csv.number(rs.getLong("id")).literal(rs.getString("kind")).literal(rs.getString("mode"))
                            .literal(rs.getString("status")).text(rs.getString("file_name")).number(rs.getLong("row_count"))
                            .number(rs.getLong("sale_count")).number(rs.getLong("line_count"))
                            .number(rs.getBigDecimal("total_amount")).number(rs.getLong("created_count"))
                            .number(rs.getLong("updated_count")).number(rs.getLong("unchanged_count"))
                            .number(rs.getLong("error_count")).text(rs.getString("imported_by"))
                            .literal(iso(rs, "created_at")));

            csv(zip, "saved_reports.csv", """
                    SELECT r.id, r.name, r.kind, r.range_type, r.date_from, r.date_to, r.relative_preset, r.store_id,
                           u.display_name AS created_by, r.created_at, r.updated_at
                    FROM saved_reports r JOIN users u ON u.id = r.created_by
                    WHERE r.business_id = :b ORDER BY r.id
                    """, b, new String[] {"id", "name", "kind", "range_type", "date_from", "date_to", "relative_preset",
                            "store_id", "created_by", "created_at", "updated_at"},
                    (rs, csv) -> {
                        csv.number(rs.getLong("id")).text(rs.getString("name")).literal(rs.getString("kind"))
                                .literal(rs.getString("range_type")).literal(text(rs.getString("date_from")))
                                .literal(text(rs.getString("date_to"))).literal(text(rs.getString("relative_preset")));
                        long store = rs.getLong("store_id");
                        (rs.wasNull() ? csv.literal("") : csv.number(store)).text(rs.getString("created_by"))
                                .literal(iso(rs, "created_at")).literal(iso(rs, "updated_at"));
                    });

            List<Map<String, Object>> charts = new ArrayList<>();
            queries.stream("""
                    SELECT c.id, c.title, c.current_revision, r.definition::text AS definition,
                           creator.display_name AS created_by, updater.display_name AS updated_by, c.created_at, c.updated_at
                    FROM chart_definitions c
                    JOIN chart_definition_revisions r
                      ON r.chart_id = c.id AND r.business_id = c.business_id AND r.revision = c.current_revision
                    JOIN users creator ON creator.id = c.created_by
                    JOIN users updater ON updater.id = c.updated_by
                    WHERE c.business_id = :b ORDER BY c.id
                    """, b, rs -> {
                        Map<String, Object> chart = new LinkedHashMap<>();
                        chart.put("id", rs.getLong("id"));
                        chart.put("title", rs.getString("title"));
                        chart.put("revision", rs.getInt("current_revision"));
                        chart.put("definition", JSON.readTree(rs.getString("definition")));
                        chart.put("createdBy", rs.getString("created_by"));
                        chart.put("updatedBy", rs.getString("updated_by"));
                        chart.put("createdAt", iso(rs, "created_at"));
                        chart.put("updatedAt", iso(rs, "updated_at"));
                        charts.add(chart);
                    });
            json(zip, "charts.json", charts);

            List<Map<String, Object>> dashboards = new ArrayList<>();
            queries.stream("""
                    SELECT d.id, d.name, d.current_revision, r.layout::text AS layout,
                           creator.display_name AS created_by, updater.display_name AS updated_by, d.created_at, d.updated_at
                    FROM dashboards d
                    JOIN dashboard_revisions r
                      ON r.dashboard_id = d.id AND r.business_id = d.business_id AND r.revision = d.current_revision
                    JOIN users creator ON creator.id = d.created_by
                    JOIN users updater ON updater.id = d.updated_by
                    WHERE d.business_id = :b ORDER BY d.id
                    """, b, rs -> {
                        Map<String, Object> dashboard = new LinkedHashMap<>();
                        dashboard.put("id", rs.getLong("id"));
                        dashboard.put("name", rs.getString("name"));
                        dashboard.put("revision", rs.getInt("current_revision"));
                        dashboard.put("layout", JSON.readTree(rs.getString("layout")));
                        dashboard.put("createdBy", rs.getString("created_by"));
                        dashboard.put("updatedBy", rs.getString("updated_by"));
                        dashboard.put("createdAt", iso(rs, "created_at"));
                        dashboard.put("updatedAt", iso(rs, "updated_at"));
                        dashboards.add(dashboard);
                    });
            json(zip, "dashboards.json", dashboards);

            zip.putNextEntry(new ZipEntry("audit_events.csv"));
            write(zip, new CsvWriter().header("id", "action", "actor_id", "actor_name", "target_type", "target_id", "details",
                    "created_at").toString());
            audit.forBusiness(b, rs -> {
                AuditEventResponse event = AuditQueries.event(rs);
                CsvWriter csv = new CsvWriter().number(event.id()).literal(event.action());
                if (event.actor() == null) {
                    csv.literal("").literal("");
                } else {
                    csv.number(event.actor().id()).text(event.actor().name());
                }
                csv.literal(event.targetType() == null ? "" : event.targetType());
                (event.targetId() == null ? csv.literal("") : csv.number(event.targetId()))
                        .text(event.details().toString()).literal(event.createdAt().toString()).endRow();
                write(zip, csv.toString());
            });
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private void csv(ZipOutputStream zip, String name, String sql, long businessId, String[] header, RowWriter row)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        write(zip, new CsvWriter().header(header).toString());
        queries.stream(sql, businessId, rs -> {
            CsvWriter csv = new CsvWriter();
            row.write(rs, csv);
            write(zip, csv.endRow().toString());
        });
        zip.closeEntry();
    }

    private static void json(ZipOutputStream zip, String name, Object value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(JSON.writeValueAsBytes(value));
        zip.closeEntry();
    }

    private static void write(OutputStream out, String text) {
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String iso(ResultSet rs, String column) throws SQLException {
        return iso(rs.getObject(column, OffsetDateTime.class));
    }

    /** An instant as ISO-8601 UTC ({@code 2026-03-01T10:00:00Z}), or empty. */
    static String iso(OffsetDateTime value) {
        return value == null ? "" : value.toInstant().toString();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
