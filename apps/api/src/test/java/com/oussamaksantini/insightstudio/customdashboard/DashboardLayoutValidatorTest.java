package com.oussamaksantini.insightstudio.customdashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oussamaksantini.insightstudio.common.web.FieldErrorsException;
import com.oussamaksantini.insightstudio.common.web.FieldErrorsException.FieldError;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every layout rule of docs/dashboards-contract.md §2, without a database: the business's charts are
 * 1, 2 and 3.
 */
class DashboardLayoutValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long BUSINESS = 1;

    private static final CustomDashboardQueries QUERIES = new CustomDashboardQueries(null) {
        @Override
        Set<Long> chartsOfBusiness(long businessId, Collection<Long> ids) {
            return ids.stream().filter(id -> businessId == BUSINESS && id <= 3).collect(Collectors.toSet());
        }
    };

    private final DashboardLayoutValidator validator = new DashboardLayoutValidator(QUERIES);

    /** Two charts side by side on desktop, stacked on mobile (the contract's example). */
    private static final String VALID = """
            {"schemaVersion": 1,
             "widgets": [{"id": "w-1a2b", "chartId": 1}, {"id": "w-9f3c", "chartId": 2}],
             "desktop": {"columns": 12, "items": [{"id": "w-1a2b", "x": 0, "y": 0, "w": 6, "h": 4},
                                                  {"id": "w-9f3c", "x": 6, "y": 0, "w": 6, "h": 4}]},
             "mobile": {"columns": 4, "items": [{"id": "w-1a2b", "x": 0, "y": 0, "w": 4, "h": 4},
                                                {"id": "w-9f3c", "x": 0, "y": 4, "w": 4, "h": 4}]}}
            """;

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    /** A layout with one widget {@code w} (chart 1) at the given desktop item, and a valid mobile grid. */
    private static String withDesktopItem(String item) {
        return """
                {"widgets": [{"id": "w", "chartId": 1}], "desktop": {"columns": 12, "items": [%s]},
                 "mobile": {"columns": 4, "items": [{"id": "w", "x": 0, "y": 0, "w": 4, "h": 2}]}}
                """.formatted(item);
    }

    private List<FieldError> errors(String layout) {
        try {
            validator.layout(json(layout), BUSINESS);
        } catch (FieldErrorsException e) {
            return e.getErrors();
        }
        throw new AssertionError("Expected the layout to be refused: " + layout);
    }

    private List<String> fields(String layout) {
        return errors(layout).stream().map(FieldError::field).toList();
    }

    @Test
    void aValidLayoutIsNormalized() {
        String messy = VALID.replace("\"schemaVersion\": 1,", "").replace("\"mobile\"", "\"mobile\"  ");
        DashboardLayout layout = validator.layout(json(messy), BUSINESS);
        assertThat(layout.toJson().toString()).isEqualTo(json(VALID).toString());
        assertThat(layout.toJson().toString()).startsWith("{\"schemaVersion\":1,\"widgets\":[{\"id\":\"w-1a2b\",\"chartId\":1}");
        assertThat(layout.chartIds()).containsExactly(1L, 2L);
        // Stored and read back: the same layout.
        assertThat(DashboardLayout.fromStored(layout.toJson())).isEqualTo(layout);
    }

    @Test
    void theEmptyLayoutIsValid() {
        DashboardLayout empty = validator.layout(DashboardLayout.empty().toJson(), BUSINESS);
        assertThat(empty).isEqualTo(DashboardLayout.empty());
        assertThat(empty.toJson().toString()).isEqualTo(
                "{\"schemaVersion\":1,\"widgets\":[],\"desktop\":{\"columns\":12,\"items\":[]},\"mobile\":{\"columns\":4,\"items\":[]}}");
    }

    @Test
    void theSameChartMayBePlacedTwice() {
        String twice = VALID.replace("\"chartId\": 2", "\"chartId\": 1");
        assertThat(validator.layout(json(twice), BUSINESS).chartIds()).containsExactly(1L);
    }

    @Test
    void unknownKeysAreRefusedAtEveryLevel() {
        String layout = VALID
                .replace("{\"schemaVersion\": 1,", "{\"schemaVersion\": 1, \"theme\": \"dark\",")
                .replace("{\"id\": \"w-1a2b\", \"chartId\": 1}", "{\"id\": \"w-1a2b\", \"chartId\": 1, \"title\": \"Mine\"}")
                .replace("\"columns\": 12,", "\"columns\": 12, \"rowHeight\": 80,")
                .replace("\"x\": 6, \"y\": 0, \"w\": 6, \"h\": 4}", "\"x\": 6, \"y\": 0, \"w\": 6, \"h\": 4, \"static\": true}");
        assertThat(errors(layout)).containsExactly(
                new FieldError("layout.theme", "Unknown field 'theme'."),
                new FieldError("layout.widgets[0].title", "Unknown field 'title'."),
                new FieldError("layout.desktop.rowHeight", "Unknown field 'rowHeight'."),
                new FieldError("layout.desktop.items[1].static", "Unknown field 'static'."));
    }

    @Test
    void theSchemaVersionMustBeOne() {
        assertThat(errors(VALID.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")))
                .containsExactly(new FieldError("layout.schemaVersion", "'schemaVersion' must be 1."));
        assertThat(fields(VALID.replace("\"schemaVersion\": 1", "\"schemaVersion\": \"1\""))).containsExactly("layout.schemaVersion");
    }

    @Test
    void theLayoutAndItsPartsMustHaveTheRightShape() {
        assertThat(fields("[]")).containsExactly("layout");
        assertThat(fields("{\"desktop\": {\"columns\": 12, \"items\": []}, \"mobile\": {\"columns\": 4, \"items\": []}}"))
                .containsExactly("layout.widgets");
        assertThat(fields(VALID.replace("\"mobile\":", "\"phone\":")))
                .containsExactly("layout.phone", "layout.mobile");
        assertThat(errors(VALID.replace("\"columns\": 4,", "\"columns\": 12,")))
                .containsExactly(new FieldError("layout.mobile.columns", "'columns' must be 4 for the mobile grid."));
        assertThat(fields(VALID.replace("\"columns\": 12,", ""))).containsExactly("layout.desktop.columns");
        assertThat(fields("{\"widgets\": [7], \"desktop\": {\"columns\": 12, \"items\": {}}, \"mobile\": {\"columns\": 4, \"items\": [3]}}"))
                .containsExactly("layout.widgets[0]", "layout.desktop.items", "layout.mobile.items[0]");
    }

    @Test
    void widgetIdsAreShortSafeAndUnique() {
        assertThat(errors(VALID.replace("\"id\": \"w-9f3c\", \"chartId\"", "\"id\": \"w-1a2b\", \"chartId\"")))
                .contains(new FieldError("layout.widgets[1].id", "Widget id 'w-1a2b' is used twice."));
        for (String bad : List.of("\"\"", "\"has space\"", "\"" + "x".repeat(41) + "\"", "\"w.1\"", "12", "null")) {
            String layout = withDesktopItem("{\"id\": \"w\", \"x\": 0, \"y\": 0, \"w\": 4, \"h\": 2}")
                    .replace("{\"id\": \"w\", \"chartId\": 1}", "{\"id\": %s, \"chartId\": 1}".formatted(bad));
            assertThat(fields(layout)).as(bad).startsWith("layout.widgets[0].id");
        }
        String longest = "A_b-9".repeat(8);
        assertThat(validator.layout(json(withDesktopItem("{\"id\": \"w\", \"x\": 0, \"y\": 0, \"w\": 4, \"h\": 2}")
                .replace("\"id\": \"w\"", "\"id\": \"" + longest + "\"")), BUSINESS).widgets())
                .containsExactly(new DashboardLayout.Widget(longest, 1));
    }

    @Test
    void chartIdsMustBeChartsOfThisBusiness() {
        assertThat(errors(VALID.replace("\"chartId\": 2", "\"chartId\": 99")))
                .containsExactly(new FieldError("layout.widgets[1].chartId", "Chart 99 is not a chart of this business."));
        for (String bad : List.of("0", "-1", "\"2\"", "2.5", "null")) {
            assertThat(errors(VALID.replace("\"chartId\": 2", "\"chartId\": " + bad))).as(bad)
                    .containsExactly(new FieldError("layout.widgets[1].chartId", "'chartId' must be the id of a saved chart."));
        }
    }

    @Test
    void atMost24Widgets() {
        String widgets = IntStream.range(0, 25).mapToObj(i -> "{\"id\": \"w%d\", \"chartId\": 1}".formatted(i))
                .collect(Collectors.joining(","));
        String desktop = IntStream.range(0, 25).mapToObj(i -> "{\"id\": \"w%d\", \"x\": 0, \"y\": %d, \"w\": 12, \"h\": 2}".formatted(i, i * 2))
                .collect(Collectors.joining(","));
        String mobile = desktop.replace("\"w\": 12", "\"w\": 4");
        String layout = "{\"widgets\": [%s], \"desktop\": {\"columns\": 12, \"items\": [%s]}, \"mobile\": {\"columns\": 4, \"items\": [%s]}}";
        assertThat(errors(layout.formatted(widgets, desktop, mobile)))
                .containsExactly(new FieldError("layout.widgets", "A dashboard can have at most 24 widgets."));
        // 24 is fine.
        String last = ",{\"id\": \"w24\", \"chartId\": 1}";
        String lastItem = ",{\"id\": \"w24\", \"x\": 0, \"y\": 48, \"w\": %d, \"h\": 2}";
        assertThat(validator.layout(json(layout.formatted(widgets.replace(last, ""), desktop.replace(lastItem.formatted(12), ""),
                mobile.replace(lastItem.formatted(4), ""))), BUSINESS).widgets()).hasSize(24);
    }

    @Test
    void everyWidgetIsPlacedExactlyOnceInEachGrid() {
        // Missing from the mobile grid (its item names the other widget instead).
        String missing = VALID.replace("{\"id\": \"w-9f3c\", \"x\": 0, \"y\": 4, \"w\": 4, \"h\": 4}",
                "{\"id\": \"w-1a2b\", \"x\": 0, \"y\": 4, \"w\": 4, \"h\": 4}");
        assertThat(errors(missing)).containsExactly(
                new FieldError("layout.mobile.items[1].id", "Widget 'w-1a2b' is placed twice in the mobile grid."),
                new FieldError("layout.mobile.items", "Widget 'w-9f3c' is not placed in the mobile grid."));
        String noMobileItems = VALID.substring(0, VALID.indexOf("\"mobile\"")) + "\"mobile\": {\"columns\": 4, \"items\": []}}";
        assertThat(errors(noMobileItems)).extracting(FieldError::message).containsExactly(
                "Widget 'w-1a2b' is not placed in the mobile grid.", "Widget 'w-9f3c' is not placed in the mobile grid.");
        // Placed twice on desktop (the second time elsewhere, so it is not an overlap).
        String twice = VALID.replace("\"x\": 6, \"y\": 0, \"w\": 6, \"h\": 4}]", "\"x\": 6, \"y\": 0, \"w\": 6, \"h\": 4}, {\"id\": \"w-9f3c\", \"x\": 0, \"y\": 8, \"w\": 6, \"h\": 4}]");
        assertThat(errors(twice)).containsExactly(
                new FieldError("layout.desktop.items[2].id", "Widget 'w-9f3c' is placed twice in the desktop grid."));
        // An item for a widget that does not exist.
        String unknown = VALID.replace("\"x\": 6, \"y\": 0, \"w\": 6, \"h\": 4}]", "\"x\": 6, \"y\": 0, \"w\": 6, \"h\": 4}, {\"id\": \"ghost\", \"x\": 0, \"y\": 8, \"w\": 6, \"h\": 4}]");
        assertThat(errors(unknown)).containsExactly(
                new FieldError("layout.desktop.items[2].id", "There is no widget 'ghost' in this layout."));
    }

    @Test
    void itemsStayInsideTheGrid() {
        assertThat(errors(withDesktopItem("{\"id\": \"w\", \"x\": -1, \"y\": -1, \"w\": 0, \"h\": 1}"))).containsExactly(
                new FieldError("layout.desktop.items[0].x", "'x' must be a whole number from 0 to 11."),
                new FieldError("layout.desktop.items[0].y", "'y' must be a whole number from 0 to 199."),
                new FieldError("layout.desktop.items[0].w", "'w' must be a whole number from 1 to 12."),
                new FieldError("layout.desktop.items[0].h", "'h' must be a whole number from 2 to 12."));
        assertThat(fields(withDesktopItem("{\"id\": \"w\", \"x\": 0, \"y\": 0, \"w\": 13, \"h\": 13}")))
                .containsExactly("layout.desktop.items[0].w", "layout.desktop.items[0].h");
        assertThat(fields(withDesktopItem("{\"id\": \"w\", \"x\": 1.5, \"y\": \"0\", \"h\": 2}")))
                .containsExactly("layout.desktop.items[0].x", "layout.desktop.items[0].y", "layout.desktop.items[0].w");
        assertThat(errors(withDesktopItem("{\"id\": \"w\", \"x\": 8, \"y\": 0, \"w\": 6, \"h\": 2}"))).containsExactly(
                new FieldError("layout.desktop.items[0].w", "The item is wider than the grid: x + w must be at most 12."));
        assertThat(errors(withDesktopItem("{\"id\": \"w\", \"x\": 0, \"y\": 195, \"w\": 6, \"h\": 6}"))).containsExactly(
                new FieldError("layout.desktop.items[0].h", "The item goes below the last row: y + h must be at most 200."));
        // The edges themselves are fine.
        validator.layout(json(withDesktopItem("{\"id\": \"w\", \"x\": 6, \"y\": 188, \"w\": 6, \"h\": 12}")), BUSINESS);
        // Mobile has 4 columns.
        String wideMobile = withDesktopItem("{\"id\": \"w\", \"x\": 0, \"y\": 0, \"w\": 4, \"h\": 2}").replace("\"w\": 4, \"h\": 2}]}}", "\"w\": 5, \"h\": 2}]}}");
        assertThat(errors(wideMobile)).containsExactly(
                new FieldError("layout.mobile.items[0].w", "'w' must be a whole number from 1 to 4."));
    }

    @Test
    void overlapsNameBothItemsButTouchingIsFine() {
        String overlapping = VALID.replace("\"x\": 6, \"y\": 0, \"w\": 6, \"h\": 4}]", "\"x\": 5, \"y\": 3, \"w\": 6, \"h\": 4}]");
        assertThat(errors(overlapping)).containsExactly(new FieldError("layout.desktop.items[1]",
                "w-9f3c overlaps w-1a2b (layout.desktop.items[0])."));
        String mobileOverlap = VALID.replace("\"x\": 0, \"y\": 4, \"w\": 4, \"h\": 4}]", "\"x\": 3, \"y\": 0, \"w\": 1, \"h\": 2}]");
        assertThat(errors(mobileOverlap)).containsExactly(new FieldError("layout.mobile.items[1]",
                "w-9f3c overlaps w-1a2b (layout.mobile.items[0])."));
        // VALID's items touch (x 0–6 and 6–12; y 0–4 and 4–8) without overlapping.
        validator.layout(json(VALID), BUSINESS);
    }

    @Test
    void allProblemsAreReportedTogetherWithTheName() {
        FieldErrorsException e = (FieldErrorsException) org.assertj.core.api.Assertions.catchThrowable(() ->
                validator.validate(json("\"  \""), json(VALID.replace("\"chartId\": 2", "\"chartId\": 99")), BUSINESS,
                        List.of(new FieldError("colour", "Unknown field 'colour'."))));
        assertThat(e.getErrors()).extracting(FieldError::field)
                .containsExactly("colour", "name", "layout.widgets[1].chartId");
    }

    @Test
    void namesAreTrimmedTextOfAtMost120Characters() {
        assertThat(validator.validate(json("\"  Weekly review \""), null, BUSINESS, List.of()).name()).isEqualTo("Weekly review");
        assertThat(validator.validate(json("\"" + "n".repeat(120) + "\""), null, BUSINESS, List.of()).name()).hasSize(120);
        for (String bad : List.of("\"\"", "\"   \"", "null", "12", "\"" + "n".repeat(121) + "\"", "\"tab\\there\"")) {
            assertThatThrownBy(() -> validator.validate(json(bad), null, BUSINESS, List.of())).as(bad)
                    .isInstanceOfSatisfying(FieldErrorsException.class,
                            ex -> assertThat(ex.getErrors()).extracting(FieldError::field).containsExactly("name"));
        }
        assertThatThrownBy(() -> validator.validate(null, null, BUSINESS, List.of()))
                .hasMessage("Enter a name for the dashboard.");
    }
}
