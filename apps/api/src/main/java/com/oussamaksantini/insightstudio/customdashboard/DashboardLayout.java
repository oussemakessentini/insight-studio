package com.oussamaksantini.insightstudio.customdashboard;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A dashboard layout (docs/dashboards-contract.md §1): the widgets (each placing a saved chart) and
 * where each one sits in the desktop and the mobile grid. Layouts from requests come only from
 * {@link DashboardLayoutValidator}; {@link #toJson} is the normalized form stored in
 * {@code dashboard_revisions.layout} and returned by the API.
 */
public record DashboardLayout(int schemaVersion, List<Widget> widgets, Grid desktop, Grid mobile) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A saved chart placed on the dashboard; the same chart may be placed by several widgets. */
    public record Widget(String id, long chartId) {
    }

    public record Grid(int columns, List<Item> items) {

        Grid without(Set<String> widgetIds) {
            return new Grid(columns, items.stream().filter(i -> !widgetIds.contains(i.id())).toList());
        }
    }

    /** The rectangle of a widget, in grid units: columns from {@code x}, rows from {@code y}. */
    public record Item(String id, int x, int y, int w, int h) {

        /** Whether the two rectangles share any area (touching edges do not count). */
        boolean overlaps(Item other) {
            return x < other.x + other.w && other.x < x + w && y < other.y + other.h && other.y < y + h;
        }
    }

    /** The layout of a new dashboard: no widgets. */
    public static DashboardLayout empty() {
        return new DashboardLayout(DashboardRules.SCHEMA_VERSION, List.of(),
                new Grid(DashboardRules.DESKTOP_COLUMNS, List.of()), new Grid(DashboardRules.MOBILE_COLUMNS, List.of()));
    }

    /** Reads a layout this API stored (already validated and normalized when it was saved). */
    public static DashboardLayout fromStored(JsonNode node) {
        List<Widget> widgets = node.path("widgets").valueStream()
                .map(w -> new Widget(w.path("id").asString(), w.path("chartId").asLong()))
                .toList();
        return new DashboardLayout(node.path("schemaVersion").asInt(DashboardRules.SCHEMA_VERSION), widgets,
                storedGrid(node.path("desktop"), DashboardRules.DESKTOP_COLUMNS),
                storedGrid(node.path("mobile"), DashboardRules.MOBILE_COLUMNS));
    }

    private static Grid storedGrid(JsonNode node, int columns) {
        return new Grid(node.path("columns").asInt(columns), node.path("items").valueStream()
                .map(i -> new Item(i.path("id").asString(), i.path("x").asInt(), i.path("y").asInt(),
                        i.path("w").asInt(), i.path("h").asInt()))
                .toList());
    }

    /** The distinct charts the widgets place, in widget order. */
    public Set<Long> chartIds() {
        Set<Long> ids = new LinkedHashSet<>();
        widgets.forEach(w -> ids.add(w.chartId()));
        return ids;
    }

    /** This layout without the given widgets (and their places in both grids). */
    public DashboardLayout without(Set<String> widgetIds) {
        return new DashboardLayout(schemaVersion, widgets.stream().filter(w -> !widgetIds.contains(w.id())).toList(),
                desktop.without(widgetIds), mobile.without(widgetIds));
    }

    /** The normalized JSON: every field present, in the contract's order, items in the order sent. */
    public ObjectNode toJson() {
        ObjectNode node = JSON.createObjectNode();
        node.put("schemaVersion", schemaVersion);
        ArrayNode widgetNodes = node.putArray("widgets");
        widgets.forEach(w -> widgetNodes.addObject().put("id", w.id()).put("chartId", w.chartId()));
        grid(node.putObject("desktop"), desktop);
        grid(node.putObject("mobile"), mobile);
        return node;
    }

    private static void grid(ObjectNode node, Grid grid) {
        node.put("columns", grid.columns());
        ArrayNode items = node.putArray("items");
        grid.items().forEach(i -> items.addObject()
                .put("id", i.id()).put("x", i.x()).put("y", i.y()).put("w", i.w()).put("h", i.h()));
    }
}
