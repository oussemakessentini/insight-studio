package com.oussamaksantini.insightstudio.customdashboard;

import java.util.regex.Pattern;

/**
 * The limits of custom dashboards and their layouts (docs/dashboards-contract.md §1, §2), enforced by
 * {@link DashboardLayoutValidator} and the service.
 */
public final class DashboardRules {

    public static final int SCHEMA_VERSION = 1;
    /** Dashboards per business. */
    public static final int MAX_DASHBOARDS = 50;
    public static final int MAX_NAME_LENGTH = 120;
    /** Widgets per layout. */
    public static final int MAX_WIDGETS = 24;
    public static final int DESKTOP_COLUMNS = 12;
    public static final int MOBILE_COLUMNS = 4;
    /** Height of an item in rows, in both grids. */
    public static final int MIN_HEIGHT = 2;
    public static final int MAX_HEIGHT = 12;
    /** No item may reach below this row ({@code y + h <= MAX_ROWS}). */
    public static final int MAX_ROWS = 200;
    /** Client-generated widget ids. */
    public static final Pattern WIDGET_ID = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");

    private DashboardRules() {
    }
}
