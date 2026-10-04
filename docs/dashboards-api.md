# Custom dashboards (API)

What the backend implements for [dashboards-contract.md](dashboards-contract.md). The contract is
binding; this page describes the code, the exact JSON, and the details the contract leaves open. The
overview dashboard (`/api/dashboard/**`, package `dashboard`) is unchanged; custom dashboards live in
the package `customdashboard`.

## Endpoints

| Method & path | Who | Answer |
|---|---|---|
| `GET /api/dashboards` | member (VIEWER+) | `200 [DashboardSummary]` by `lower(name)`, then id |
| `POST /api/dashboards` | `require(Role.ADMIN)` | body `{name, layout?}` → `201 Dashboard` (revision 1; empty layout when `layout` is absent or `null`) |
| `GET /api/dashboards/{id}` | member | `200 Dashboard` (current revision); `?revision=n` for an older one |
| `PUT /api/dashboards/{id}` | `require(Role.ADMIN)` | body `{name, layout, expectedRevision}` → `200 Dashboard` (next revision) |
| `POST /api/dashboards/{id}/duplicate` | `require(Role.ADMIN)` | body `{name?}` or none → `201 Dashboard` (revision 1) |
| `DELETE /api/dashboards/{id}` | `require(Role.ADMIN)` | `204` (revisions and references deleted by the foreign keys' cascade) |
| `GET /api/dashboards/{id}/revisions` | member | `200 [DashboardRevision]`, newest first |
| `GET /api/charts/{id}/dashboards` | member | `200 [{id, name}]` by `lower(name)`: dashboards whose **current** layout places the chart |

- The business is always `CurrentBusiness` (membership, `X-Business-Id` as selector); dashboards,
  revisions and charts are looked up by `(id, business_id)`, so another business's dashboard id is
  `404 "Dashboard not found."` on every endpoint and its chart id `404 "Chart not found."` on
  `/api/charts/{id}/dashboards` (and a `400` inside a layout, below).
- `/api/dashboards/**` is not demo-readable (`/api/dashboard/**` of the overview is): anonymous callers
  get `401`, also with the public demo on.
- Order of checks for writes: membership and role, then (`PUT`, duplicate, `DELETE`) the dashboard's
  existence, then the body. For `PUT`: unknown body fields and `expectedRevision`, then the revision
  check (`409`), then name and layout (`400`), then the name's uniqueness (`409`).

## JSON shapes

`Dashboard`:

```json
{"id": 4, "name": "Weekly review", "revision": 3,
 "layout": {"schemaVersion": 1,
            "widgets": [{"id": "w-1a2b", "chartId": 12}, {"id": "w-9f3c", "chartId": 7}],
            "desktop": {"columns": 12, "items": [{"id": "w-1a2b", "x": 0, "y": 0, "w": 6, "h": 4},
                                                 {"id": "w-9f3c", "x": 6, "y": 0, "w": 6, "h": 4}]},
            "mobile": {"columns": 4, "items": [{"id": "w-1a2b", "x": 0, "y": 0, "w": 4, "h": 4},
                                               {"id": "w-9f3c", "x": 0, "y": 4, "w": 4, "h": 4}]}},
 "widgets": [{"id": "w-1a2b", "chartId": 12, "missing": false,
              "chart": {"id": 12, "title": "Revenue by store", "visualization": "bar", "revision": 2}},
             {"id": "w-9f3c", "chartId": 7, "missing": true, "chart": null}],
 "createdBy": "Ada", "updatedBy": "Grace",
 "createdAt": "2026-10-03T09:00:00Z", "updatedAt": "2026-10-03T09:30:00Z"}
```

- `layout` is the **normalized** layout: every key present, in the order above (`schemaVersion`
  defaulted to 1 when sent without it), widgets and items in the order sent. It is what was stored,
  rebuilt in this key order (PostgreSQL `jsonb` keeps its own).
- `widgets` follows the layout's widget order; `chart` is the chart's **current** title, visualization
  and revision (the reference is the chart id), or `null` with `missing: true` once the chart is deleted.
- With `?revision=n`: `revision`, `name` and `layout` are that revision's; `updatedBy`/`updatedAt` are
  who saved it and when; `createdBy`/`createdAt` are the dashboard's. The widgets still show the charts
  as they are now. Read-only: a `PUT` always needs the current revision as `expectedRevision`.
  `404 "Revision 3 of this dashboard was not found."` for an unknown revision.

`DashboardSummary` (no layout):

```json
{"id": 4, "name": "Weekly review", "revision": 3, "widgetCount": 2, "missingCount": 1,
 "updatedBy": "Grace", "updatedAt": "2026-10-03T09:30:00Z"}
```

`missingCount`: widgets of the current layout whose chart is not (any more) a chart of the business.

`DashboardRevision`: `{"revision": 3, "name": "Weekly review", "widgetCount": 2, "createdBy": "Grace",
"createdAt": "..."}`.

`GET /api/charts/{id}/dashboards`: `[{"id": 4, "name": "Weekly review"}]` (`[]` when unused).

## Layout rules (`DashboardLayoutValidator`)

Exactly the contract's §2; all problems are reported at once as `400` with `errors: [{field, message}]`
(`detail` joins the messages). Limits are in `DashboardRules`. Fields are JSON paths **from the request
body**:

| Rule | Field | Message |
|---|---|---|
| name required, trimmed, ≤ 120, no control characters | `name` | `Enter a name for the dashboard.` · `'name' must be text.` · `The name may be at most 120 characters.` · `The name contains invalid characters.` |
| unknown body field | its name (`colour`) | `Unknown field 'colour'.` |
| unknown key anywhere in the layout | `layout.theme`, `layout.widgets[0].title`, `layout.desktop.rowHeight`, `layout.desktop.items[1].static` | `Unknown field 'title'.` |
| layout must be an object | `layout` | `The layout must be a JSON object.` |
| `schemaVersion` (optional) is 1 | `layout.schemaVersion` | `'schemaVersion' must be 1.` |
| widgets list | `layout.widgets` | `'widgets' must be a list of {"id", "chartId"}.` |
| 0–24 widgets | `layout.widgets` | `A dashboard can have at most 24 widgets.` |
| widget shape | `layout.widgets[2]` | `Each widget must be {"id", "chartId"}.` |
| widget id `^[A-Za-z0-9_-]{1,40}$` | `layout.widgets[0].id` | `A widget id is 1 to 40 letters, digits, '_' or '-'.` |
| unique widget ids | `layout.widgets[1].id` | `Widget id 'w-1a2b' is used twice.` |
| `chartId` a positive whole number | `layout.widgets[0].chartId` | `'chartId' must be the id of a saved chart.` |
| `chartId` a chart of **this** business (unknown, deleted, or another business's) | `layout.widgets[0].chartId` | `Chart 12 is not a chart of this business.` |
| both grids present | `layout.desktop` / `layout.mobile` | `The layout needs a mobile grid: {"columns": 4, "items": [...]}.` |
| `columns` exactly 12 / 4 | `layout.desktop.columns` | `'columns' must be 12 for the desktop grid.` |
| items list / item shape | `layout.desktop.items`, `layout.desktop.items[0]` | `'items' must be a list of ...` · `Each item must be ...` |
| item names a widget | `layout.desktop.items[2].id` | `'id' must be the id of a widget.` · `There is no widget 'ghost' in this layout.` |
| at most once per grid | `layout.mobile.items[1].id` | `Widget 'w-1a2b' is placed twice in the mobile grid.` |
| at least once per grid | `layout.mobile.items` | `Widget 'w-9f3c' is not placed in the mobile grid.` |
| whole numbers in range: `0 ≤ x ≤ columns−1`, `0 ≤ y ≤ 199`, `1 ≤ w ≤ columns`, `2 ≤ h ≤ 12` | `layout.desktop.items[1].x` / `.y` / `.w` / `.h` | `'w' must be a whole number from 1 to 12.` |
| `x + w ≤ columns` | `layout.desktop.items[1].w` | `The item is wider than the grid: x + w must be at most 12.` |
| `y + h ≤ 200` | `layout.desktop.items[1].h` | `The item goes below the last row: y + h must be at most 200.` |
| no overlaps (touching is fine) | the later item, `layout.desktop.items[2]` | `w-9f3c overlaps w-1a2b (layout.desktop.items[0]).` |

- Chart ids are checked with one query by `(id, business_id)` and the message never says whether the id
  exists elsewhere. On writes the query locks the chart rows (`FOR KEY SHARE`) until the transaction
  ends, so a chart cannot be deleted between the check and the reference rows.
- Integers must be JSON integers (`4.0`, `"4"` are refused). The same chart may be placed by several
  widgets.

## Revisions, concurrency and references

- `dashboards` (Flyway V15) holds the current name, `current_revision`, creator and last editor; every
  save (a rename too) inserts an immutable `dashboard_revisions` row with the name and the normalized
  layout, and rewrites `dashboard_chart_refs` (the distinct charts of the layout), all in one
  transaction.
- `PUT` compares `expectedRevision` with the current revision, validates, then
  `UPDATE dashboards SET current_revision = current_revision + 1 ... WHERE current_revision = :expected`;
  zero rows (someone saved in between, the row lock makes the second writer re-check) is the same `409`.
  Nothing is written on a `409`. The stale `409` (`common.web.StaleRevisionException`, rendered by
  `ApiExceptionHandler`) carries the current state:

  ```json
  {"type": "about:blank", "title": "Conflict", "status": 409,
   "detail": "This dashboard was changed by someone else since you opened it. Reload it to see the latest version, then make your changes again.",
   "instance": "/api/dashboards/4", "currentRevision": 3, "updatedBy": "Grace", "updatedAt": "2026-10-03T09:30:00Z"}
  ```
- Names are unique per business ignoring case (`409 "A dashboard named 'Weekly' already exists."`,
  checked first; the unique index decides races). Creating and duplicating lock the business row
  (`FOR NO KEY UPDATE`) so the 50-dashboard limit holds under concurrent requests:
  `409 "A business can have at most 50 dashboards. Delete one before adding another."`.
- Duplicate copies the **current** layout without the widgets whose chart is gone (from `widgets` and
  both grids; the remaining items keep their places), re-validates it, and creates revision 1. Without a
  name: `Copy of <name>`, then `Copy of <name> (2)`, … (shortened to fit 120 characters); a given name
  that is taken is a `409`. Body: `{"name": "..."}`, `{}` or none; other fields are `400`.
- The database is the second line of defence: `dashboard_chart_refs` has composite foreign keys to
  `dashboards(id, business_id)` and `chart_definitions(id, business_id)`, so a reference to another
  business's chart fails even if the validator were bypassed (tested by writing one directly).

## Charts that change or disappear

- Widgets reference charts by id: editing a chart changes the widget's `chart.title` and
  `chart.revision` on every dashboard, without a dashboard revision.
- Deleting a chart is allowed; its reference rows go with it (cascade). The layout keeps the widget,
  reported as `missing: true, chart: null`, and the summary counts it in `missingCount`. Saving a layout
  that still contains it is `400` on `layout.widgets[i].chartId` (`Chart 12 is not a chart of this
  business.`); removing the widget and saving works. `GET /api/charts/{id}/dashboards` lists the
  dashboards using a chart before it is deleted (from `dashboard_chart_refs`, i.e. current layouts only).

## Chart run limit (`chart.ChartRunLimiter`)

- At most `insight.charts.max-concurrent-runs-per-business` (`CHARTS_MAX_CONCURRENT_RUNS_PER_BUSINESS`,
  default **6**) chart runs in progress per business **across all API instances** (`chart_run_slots`,
  Flyway V20; docs/operations.md), counting
  `GET /api/charts/{id}/data` and `POST /api/charts/preview` together. One more is refused immediately:

  ```
  HTTP/1.1 429
  Retry-After: 1
  Content-Type: application/problem+json

  {"type": "about:blank", "title": "Too Many Requests", "status": 429,
   "detail": "Too many charts are loading for this business right now. Try again in a moment.",
   "instance": "/api/charts/12/data"}
  ```
- The slot is taken in `ChartController` after the access check (so `401`/`403`/`404 Business not
  found` come first) and before `ChartService` opens its transaction: a refused run takes no database
  connection and runs no engine (no `X-Report-Engine` header). It is released when the request ends,
  whatever the outcome (try-with-resources).
- A counter per business in a `ConcurrentHashMap`, updated atomically, removed when it drops to zero.
  Chart results are unchanged.

## Tests

- `customdashboard/DashboardLayoutValidatorTest`: every rule above with its field and message, the
  normalized form, touching vs overlapping, 24 vs 25 widgets, the edges of the grid.
- `customdashboard/CustomDashboardApiIntegrationTest` (PostgreSQL): create (empty layout), save and
  reload desktop/mobile layouts as the same normalized JSON (also in the database), revisions readable,
  renames bump the revision, list order and counts, name uniqueness, the 50 limit, delete; every rule
  over HTTP (overlap, bounds, missing from a grid, duplicate ids, unknown keys, 25 widgets, unknown and
  another business's chart ids); duplication (layout copied, missing widgets dropped, unique names,
  revision 1); a stale save (409 with `currentRevision`, `updatedBy`, `updatedAt`) and a two-thread race
  (exactly one wins, five rounds); deleted charts (`missing`, `missingCount`, 400 on save, removal saves),
  edited charts; `GET /api/charts/{id}/dashboards`; the permission matrix (VIEWER, ADMIN, OWNER,
  unverified, anonymous, CSRF); cross-business 404s on every endpoint, 400 for its chart ids,
  `X-Business-Id` tricks, and the composite foreign key refusing a cross-business reference written
  without the validator.
- `customdashboard/CustomDashboardPublicDemoIntegrationTest`: `401` for the public demo on every
  endpoint while the overview stays readable.
- `chart/ChartRunLimitIntegrationTest`: an instance with a limit of 1; another connection locks
  `sale_items` so a run stays in progress; a second run (data and preview) of the same business is an
  immediate `429` with `Retry-After: 1`, another business's run is not refused; after the lock is
  released both finish and runs succeed again (also after failed runs).

## Limitations

- The run limit is shared by every API instance (one row per run in `chart_run_slots`, taken in a short
  transaction serialised per business; a crashed instance's slots stop counting after
  `insight.charts.run-slot-ttl`, 2 minutes). The client queue (3 per tab, contract §6) keeps normal use
  well below it.
- `GET /api/charts/{id}/dashboards` reflects current layouts only; older revisions that placed the
  chart are not listed.
- Viewing an older revision shows the charts as they are now (charts are referenced by id, not by
  revision).
