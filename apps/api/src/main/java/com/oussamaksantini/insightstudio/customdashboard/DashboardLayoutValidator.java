package com.oussamaksantini.insightstudio.customdashboard;

import com.oussamaksantini.insightstudio.common.web.FieldErrorsException;
import com.oussamaksantini.insightstudio.common.web.FieldErrorsException.FieldError;
import com.oussamaksantini.insightstudio.customdashboard.DashboardLayout.Grid;
import com.oussamaksantini.insightstudio.customdashboard.DashboardLayout.Item;
import com.oussamaksantini.insightstudio.customdashboard.DashboardLayout.Widget;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Checks dashboard names and layouts sent as JSON against every rule of docs/dashboards-contract.md
 * §1–§2 ({@link DashboardRules}) and against the business: every {@code chartId} must be a chart of
 * this business (never confirming other businesses' ids). All problems are reported at once, each
 * with its field as a JSON path from the request body ({@code name}, {@code layout.widgets[0].chartId},
 * {@code layout.desktop.items[1].w}), as a 400 {@link FieldErrorsException}.
 *
 * <p>Fields the contract does not define are refused at every level, so a layout never carries chart
 * settings or anything else.
 */
@Component
class DashboardLayoutValidator {

    private static final Set<String> LAYOUT_FIELDS = Set.of("schemaVersion", "widgets", "desktop", "mobile");
    private static final Set<String> WIDGET_FIELDS = Set.of("id", "chartId");
    private static final Set<String> GRID_FIELDS = Set.of("columns", "items");
    private static final Set<String> ITEM_FIELDS = Set.of("id", "x", "y", "w", "h");

    private final CustomDashboardQueries queries;

    DashboardLayoutValidator(CustomDashboardQueries queries) {
        this.queries = queries;
    }

    /** A valid name and, when one was sent, layout. */
    record Validated(String name, DashboardLayout layout) {
    }

    /**
     * The name and the layout of a request body, every problem of both (and {@code earlier} ones, such
     * as unknown body fields) reported together.
     *
     * @param name the {@code name} node; required
     * @param layout the {@code layout} node; {@code null} when the request has none
     * @throws FieldErrorsException 400 listing every invalid field
     */
    Validated validate(JsonNode name, JsonNode layout, long businessId, List<FieldError> earlier) {
        Errors errors = new Errors();
        earlier.forEach(e -> errors.add(e.field(), e.message()));
        String validName = name(name, errors);
        DashboardLayout validLayout = layout == null ? null : layout(layout, businessId, errors);
        errors.throwIfAny();
        return new Validated(validName, validLayout);
    }

    /**
     * A layout on its own (e.g. a copy), normalized.
     *
     * @throws FieldErrorsException 400 listing every invalid field
     */
    DashboardLayout layout(JsonNode node, long businessId) {
        Errors errors = new Errors();
        DashboardLayout layout = layout(node, businessId, errors);
        errors.throwIfAny();
        return layout;
    }

    // ---------------------------------------------------------------- name

    private static String name(JsonNode node, Errors errors) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            errors.add("name", "Enter a name for the dashboard.");
            return null;
        }
        if (!node.isString()) {
            errors.add("name", "'name' must be text.");
            return null;
        }
        String name = node.asString().strip();
        if (name.isEmpty()) {
            errors.add("name", "Enter a name for the dashboard.");
        } else if (name.length() > DashboardRules.MAX_NAME_LENGTH) {
            errors.add("name", "The name may be at most %d characters.".formatted(DashboardRules.MAX_NAME_LENGTH));
        } else if (name.chars().anyMatch(Character::isISOControl)) {
            errors.add("name", "The name contains invalid characters.");
        }
        return name;
    }

    // ---------------------------------------------------------------- layout

    private DashboardLayout layout(JsonNode node, long businessId, Errors errors) {
        if (node == null || !node.isObject()) {
            errors.add("layout", "The layout must be a JSON object.");
            return null;
        }
        int before = errors.size();
        unknownFields(node, LAYOUT_FIELDS, "layout", errors);
        JsonNode schemaVersion = node.get("schemaVersion");
        if (schemaVersion != null && !schemaVersion.isNull()
                && !(schemaVersion.isIntegralNumber() && schemaVersion.asLong() == DashboardRules.SCHEMA_VERSION)) {
            errors.add("layout.schemaVersion", "'schemaVersion' must be %d.".formatted(DashboardRules.SCHEMA_VERSION));
        }

        // Widgets: every id an item may refer to, and the chart each one places.
        List<Widget> widgets = new ArrayList<>();
        Set<String> widgetIds = new LinkedHashSet<>();
        Map<Integer, Long> chartIdsByIndex = new LinkedHashMap<>();
        JsonNode widgetNodes = node.get("widgets");
        if (widgetNodes == null || !widgetNodes.isArray()) {
            errors.add("layout.widgets", "'widgets' must be a list of {\"id\", \"chartId\"}.");
        } else {
            if (widgetNodes.size() > DashboardRules.MAX_WIDGETS) {
                errors.add("layout.widgets",
                        "A dashboard can have at most %d widgets.".formatted(DashboardRules.MAX_WIDGETS));
            }
            for (int i = 0; i < widgetNodes.size(); i++) {
                String path = "layout.widgets[%d]".formatted(i);
                JsonNode widget = widgetNodes.get(i);
                if (!widget.isObject()) {
                    errors.add(path, "Each widget must be {\"id\", \"chartId\"}.");
                    continue;
                }
                unknownFields(widget, WIDGET_FIELDS, path, errors);
                String id = widgetId(widget.get("id"), path + ".id", errors);
                if (id != null && !widgetIds.add(id)) {
                    errors.add(path + ".id", "Widget id '%s' is used twice.".formatted(id));
                    id = null;
                }
                Long chartId = chartId(widget.get("chartId"), path + ".chartId", errors);
                if (chartId != null) {
                    chartIdsByIndex.put(i, chartId);
                }
                if (id != null && chartId != null) {
                    widgets.add(new Widget(id, chartId));
                }
            }
        }

        Grid desktop = grid(node.get("desktop"), "desktop", DashboardRules.DESKTOP_COLUMNS, widgetIds, errors);
        Grid mobile = grid(node.get("mobile"), "mobile", DashboardRules.MOBILE_COLUMNS, widgetIds, errors);

        // Charts of this business only, looked up once by (id, business). A deleted chart is no longer
        // one, so a layout still placing it is refused too.
        if (!chartIdsByIndex.isEmpty()) {
            Set<Long> own = queries.chartsOfBusiness(businessId, new HashSet<>(chartIdsByIndex.values()));
            chartIdsByIndex.forEach((index, chartId) -> {
                if (!own.contains(chartId)) {
                    errors.add("layout.widgets[%d].chartId".formatted(index),
                            "Chart %d is not a chart of this business.".formatted(chartId));
                }
            });
        }
        return errors.size() == before
                ? new DashboardLayout(DashboardRules.SCHEMA_VERSION, List.copyOf(widgets), desktop, mobile)
                : null;
    }

    private static String widgetId(JsonNode node, String path, Errors errors) {
        if (node == null || !node.isString() || !DashboardRules.WIDGET_ID.matcher(node.asString()).matches()) {
            errors.add(path, "A widget id is 1 to 40 letters, digits, '_' or '-'.");
            return null;
        }
        return node.asString();
    }

    private static Long chartId(JsonNode node, String path, Errors errors) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 1) {
            errors.add(path, "'chartId' must be the id of a saved chart.");
            return null;
        }
        return node.asLong();
    }

    /**
     * One grid: its column count, every item's fields and bounds, every widget placed exactly once,
     * and no two items overlapping. {@code null} when anything is invalid.
     */
    private static Grid grid(JsonNode node, String name, int columns, Set<String> widgetIds, Errors errors) {
        String path = "layout." + name;
        if (node == null || !node.isObject()) {
            errors.add(path, "The layout needs a %s grid: {\"columns\": %d, \"items\": [...]}.".formatted(name, columns));
            return null;
        }
        int before = errors.size();
        unknownFields(node, GRID_FIELDS, path, errors);
        JsonNode columnsNode = node.get("columns");
        if (columnsNode == null || !columnsNode.isIntegralNumber() || columnsNode.asLong() != columns) {
            errors.add(path + ".columns", "'columns' must be %d for the %s grid.".formatted(columns, name));
        }
        JsonNode itemNodes = node.get("items");
        if (itemNodes == null || !itemNodes.isArray()) {
            errors.add(path + ".items", "'items' must be a list of {\"id\", \"x\", \"y\", \"w\", \"h\"}.");
            return null;
        }

        List<Item> items = new ArrayList<>();
        List<Integer> placedAt = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (int i = 0; i < itemNodes.size(); i++) {
            String itemPath = "%s.items[%d]".formatted(path, i);
            JsonNode itemNode = itemNodes.get(i);
            if (!itemNode.isObject()) {
                errors.add(itemPath, "Each item must be {\"id\", \"x\", \"y\", \"w\", \"h\"}.");
                continue;
            }
            unknownFields(itemNode, ITEM_FIELDS, itemPath, errors);
            JsonNode idNode = itemNode.get("id");
            String id = idNode != null && idNode.isString() ? idNode.asString() : null;
            boolean known = true;
            if (id == null) {
                errors.add(itemPath + ".id", "'id' must be the id of a widget.");
                known = false;
            } else if (!widgetIds.contains(id)) {
                errors.add(itemPath + ".id", "There is no widget '%s' in this layout.".formatted(id));
                known = false;
            } else if (!placed.add(id)) {
                errors.add(itemPath + ".id", "Widget '%s' is placed twice in the %s grid.".formatted(id, name));
                known = false;
            }
            Integer x = whole(itemNode.get("x"), itemPath + ".x", "x", 0, columns - 1, errors);
            Integer y = whole(itemNode.get("y"), itemPath + ".y", "y", 0, DashboardRules.MAX_ROWS - 1, errors);
            Integer w = whole(itemNode.get("w"), itemPath + ".w", "w", 1, columns, errors);
            Integer h = whole(itemNode.get("h"), itemPath + ".h", "h", DashboardRules.MIN_HEIGHT,
                    DashboardRules.MAX_HEIGHT, errors);
            if (x == null || y == null || w == null || h == null) {
                continue;
            }
            boolean inside = true;
            if (x + w > columns) {
                errors.add(itemPath + ".w", "The item is wider than the grid: x + w must be at most %d.".formatted(columns));
                inside = false;
            }
            if (y + h > DashboardRules.MAX_ROWS) {
                errors.add(itemPath + ".h", "The item goes below the last row: y + h must be at most %d."
                        .formatted(DashboardRules.MAX_ROWS));
                inside = false;
            }
            if (known && inside) {
                // Rectangles may touch, not intersect. The later item names the earlier one. (Bounded:
                // past the widget limit the layout is refused anyway.)
                Item item = new Item(id, x, y, w, h);
                for (int j = 0; j < items.size() && items.size() <= DashboardRules.MAX_WIDGETS; j++) {
                    if (item.overlaps(items.get(j))) {
                        errors.add(itemPath, "%s overlaps %s (%s.items[%d]).".formatted(
                                id, items.get(j).id(), path, placedAt.get(j)));
                    }
                }
                items.add(item);
                placedAt.add(i);
            }
        }
        for (String id : widgetIds) {
            if (!placed.contains(id)) {
                errors.add(path + ".items", "Widget '%s' is not placed in the %s grid.".formatted(id, name));
            }
        }
        return errors.size() == before ? new Grid(columns, List.copyOf(items)) : null;
    }

    /** A whole number from {@code min} to {@code max}, or {@code null} (with an error). */
    private static Integer whole(JsonNode node, String path, String field, int min, int max, Errors errors) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < min || node.asInt() > max) {
            errors.add(path, "'%s' must be a whole number from %d to %d.".formatted(field, min, max));
            return null;
        }
        return node.asInt();
    }

    private static void unknownFields(JsonNode object, Set<String> known, String path, Errors errors) {
        object.properties().stream().map(Map.Entry::getKey).filter(name -> !known.contains(name))
                .forEach(name -> errors.add(path + "." + name, "Unknown field '%s'.".formatted(name)));
    }

    /** Collected field errors, in the order found. */
    private static final class Errors {

        private final List<FieldError> list = new ArrayList<>();

        void add(String field, String message) {
            list.add(new FieldError(field, message));
        }

        int size() {
            return list.size();
        }

        void throwIfAny() {
            if (!list.isEmpty()) {
                throw new FieldErrorsException(list);
            }
        }
    }
}
