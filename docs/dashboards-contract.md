# Customizable dashboards: contract

Binding agreement between the integrator, the **backend** agent and the **frontend** agent. Change
it only through the integrator. The existing overview dashboard (`/`, `/api/dashboard/**`) stays
exactly as it is; custom dashboards live next to it.

## 1. Model

A dashboard has a `name` (1–120 characters, trimmed, no control characters, unique per business
ignoring case) and a **layout** (JSONB, `schemaVersion: 1`):

```json
{
  "schemaVersion": 1,
  "widgets": [ { "id": "w-1a2b", "chartId": 12 }, { "id": "w-9f3c", "chartId": 7 } ],
  "desktop": { "columns": 12, "items": [ { "id": "w-1a2b", "x": 0, "y": 0, "w": 6, "h": 4 },
                                          { "id": "w-9f3c", "x": 6, "y": 0, "w": 6, "h": 4 } ] },
  "mobile":  { "columns": 4,  "items": [ { "id": "w-1a2b", "x": 0, "y": 0, "w": 4, "h": 4 },
                                          { "id": "w-9f3c", "x": 0, "y": 4, "w": 4, "h": 4 } ] }
}
```

- Widget `id`: client-generated, `^[A-Za-z0-9_-]{1,40}$`, unique in the layout. The same chart may be
  placed more than once (different widget ids).
- Grid units: desktop 12 columns, mobile 4 columns; one row = 80 px on screen (the UI's choice; the
  API only sees units).

## 2. Layout rules (validated on the server; `400` with `errors: [{field, message}]`)

- `schemaVersion` = 1; no unknown keys anywhere (a layout carries no chart settings).
- 0–24 widgets per dashboard.
- Every widget appears **exactly once** in `desktop.items` **and** in `mobile.items`; no item refers to
  an unknown widget; `columns` are exactly 12 (desktop) and 4 (mobile).
- Integers: `x ≥ 0`, `y ≥ 0`, `x + w ≤ columns`, `1 ≤ w ≤ columns`, desktop `2 ≤ h ≤ 12`, mobile
  `2 ≤ h ≤ 12`, `y + h ≤ 200`.
- **No overlaps** within a grid (rectangles may touch, not intersect).
- Every `chartId` is a chart of **this** business (looked up by `(id, business_id)`); otherwise `400`
  `"Chart 12 is not a chart of this business."` (never confirms other businesses' ids). Enforced again by
  the database (`dashboard_chart_refs` composite foreign key).
- Field paths: `name`, `layout.widgets[0].chartId`, `layout.desktop.items[1].w`, … ; overlaps name both
  items (`layout.desktop.items[2]` "overlaps w-1a2b").

## 3. Charts that change or disappear

- A widget shows its chart's **current** revision (the reference is the chart id). Editing a chart
  updates every dashboard that shows it; the widget header shows the chart's current title.
- **Deleting a chart is allowed.** Its reference rows are removed (database cascade); the layout keeps
  the widget, and the API reports it as `chart: null` (`missing: true`). The UI shows a "This chart was
  deleted" placeholder card; owners/admins can remove it (a normal layout save). Saving a layout that
  still contains a missing chart's widget is **refused** (`400`, the chart is no longer of this
  business), so the editor removes missing widgets before saving and says so.
- Before deleting a chart, the UI warns which dashboards use it: `GET /api/charts/{id}/dashboards`.

## 4. API (business from `CurrentBusiness` everywhere; ids looked up by `(id, business_id)`)

| Method & path | Who | Answer |
|---|---|---|
| `GET /api/dashboards` | VIEWER+ | `[DashboardSummary]` by name |
| `POST /api/dashboards` | ADMIN+ | body `{name, layout?}` (empty layout when omitted) → `201 Dashboard` (revision 1) |
| `GET /api/dashboards/{id}` | VIEWER+ | `Dashboard` (current revision); `?revision=n` for an older one (read-only) |
| `PUT /api/dashboards/{id}` | ADMIN+ | body `{name, layout, expectedRevision}` → `200 Dashboard` (new revision). Rename and layout changes are both saves. Stale `expectedRevision` → `409` (below) |
| `POST /api/dashboards/{id}/duplicate` | ADMIN+ | body `{name?}` → `201 Dashboard`: current layout copied (missing-chart widgets dropped), revision 1, default name "Copy of …" made unique |
| `DELETE /api/dashboards/{id}` | ADMIN+ | `204` (revisions and references deleted) |
| `GET /api/dashboards/{id}/revisions` | VIEWER+ | `[{revision, name, widgetCount, createdBy, createdAt}]` newest first |
| `GET /api/charts/{id}/dashboards` | VIEWER+ | `[{id, name}]` dashboards whose current layout uses the chart |

`Dashboard` = `{id, name, revision, layout, widgets: [{id, chartId, missing, chart: {id, title,
visualization, revision} | null}], createdBy, updatedBy, createdAt, updatedAt}` (`widgets` in layout
order; `chart` is the live chart summary or `null` when deleted).

`DashboardSummary` = `{id, name, revision, widgetCount, missingCount, updatedBy, updatedAt}`.

`409` for a stale save: problem detail `"This dashboard was changed by someone else since you opened
it…"` plus `currentRevision`, `updatedBy`, `updatedAt`, so the UI can offer "Reload" (losing local
changes) or "Keep editing". Title conflicts are `409 "A dashboard named '…' already exists."`.

Limits: 50 dashboards per business (`409`), 24 widgets per layout (`400`).

Widgets run with the existing `GET /api/charts/{chartId}/data` (unchanged permissions: VIEWER+), with
its `503`/`Retry-After` answers. **Server-side concurrency limit**: at most 6 chart data runs in progress
per business across all API instances (since V20; per instance before); more → `429` problem detail with
`Retry-After: 1` (no work started).
The public demo has no custom dashboards (anonymous → `401`).

## 5. Storage (integrator: Flyway `V15__create_dashboards.sql`, done)

`dashboards`, `dashboard_revisions(dashboard_id, business_id, revision, name, schema_version, layout
jsonb, …)`, `dashboard_chart_refs(dashboard_id, business_id, chart_id)` with composite foreign keys to
`dashboards(id, business_id)` and `chart_definitions(id, business_id)` (cascade on delete). A save
writes the revision, bumps `current_revision` with a conditional update (`WHERE current_revision =
:expected`, 0 rows → `409`), and rewrites the reference rows, all in one transaction.

## 6. Web app

- Nav: **Overview** (the existing `/`) and **Dashboards** (`/dashboards`). The existing overview page is
  unchanged.
- `/dashboards`: list (name, widgets, missing charts, updated by/at); ADMIN+: New, Rename, Duplicate,
  Delete (confirm).
- `/dashboards/{id}`: view. Widgets in the desktop grid (≥ 1024 px wide) or the mobile grid (narrower);
  each widget loads, shows empty results and failures (with Retry) **independently**, through a client
  queue of at most **3** concurrent chart requests (429 → retry after `Retry-After`). Refresh reruns all
  widgets. `?revision=n` shows an older revision read-only.
- `/dashboards/{id}/edit` (ADMIN+): add charts (picker of saved charts), remove, move and resize with
  drag-and-drop **and** accessible controls (buttons and keyboard: move left/right/up/down, wider/
  narrower/taller/shorter, with live-region announcements), separate desktop and mobile layout tabs,
  rename, Save (`expectedRevision`; 409 → reload or keep editing), Cancel. Leaving with unsaved changes
  (in-app navigation, back/forward, refresh/close) asks for confirmation.
- Viewers: list and view only. Unverified admins: explain like saved reports/charts.
- Chart page: deleting a chart lists the dashboards that use it before confirming.
- Desktop (1440) and mobile (390) without page overflow; follows the `dataviz` skill for widget charts
  (reuse the existing chart renderer).

## 7. Ownership

| Owner | Files |
|---|---|
| **Integrator** | `apps/api/pom.xml`, `db/migration/**` (V15 done), this contract, `README.md`, `infra/**`, merges |
| **backend** agent | `apps/api/**` except the above; `docs/dashboards-api.md` |
| **frontend** agent | `apps/web/**` (including `package.json` if a drag-and-drop dependency is needed); `docs/frontend-dashboards.md` |
