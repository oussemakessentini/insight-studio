# Chart builder: contract

Binding agreement between the integrator, the **backend** agent and the **frontend** agent. Change
it only through the integrator. Everything that exists keeps working; `REPORTS_ENGINE=sql` stays the
default for reports. Dashboard layouts (drag and drop) are the **next** phase: chart definitions here
must not contain layout (position, size, dashboard id).

## 1. The model

A chart definition (JSON, `schemaVersion: 1`):

```json
{
  "schemaVersion": 1,
  "title": "Revenue by store, last 90 days",
  "visualization": "bar",
  "metrics": ["revenue"],
  "groupBy": "store",
  "granularity": null,
  "range": { "type": "relative", "preset": "last_90_days" },
  "filters": { "storeIds": [], "categories": ["Tops"], "productIds": [] },
  "limit": 10,
  "engine": "sql"
}
```

| Field | Values |
|---|---|
| `visualization` | `kpi`, `line`, `bar`, `pie`, `table` |
| `metrics` | non-empty, distinct, from `revenue`, `orders`, `units`, `average_order_value` |
| `groupBy` | `none`, `time`, `store`, `product`, `category` |
| `granularity` | `day`, `week`, `month` when `groupBy = time`, otherwise `null` |
| `range` | as saved reports: `{type:"fixed", from, to}` or `{type:"relative", preset}` (the saved-report presets, resolved against today in the business time zone, `savedreport.RelativePreset`) |
| `filters` | `storeIds`, `productIds` (ids of this business), `categories` (names); each ≤ 50 entries; empty = all |
| `limit` | groups shown for `store`/`product`/`category` grouping: 1–50 (default 10), the top groups by the first metric; `null` otherwise |
| `engine` | `sql` (default) or `cube` (only when Cube is configured) |

### Metric definitions (identical to the report API)

- **revenue** = Σ quantity × unit price charged (historical prices; never `list_price`).
- **orders** = receipts with at least one item. Grouped by **product** or **category**: receipts
  containing that product/category (a receipt with two categories counts once in each, so groups do
  not add up to the total — exactly like the category report).
- **units** = Σ quantity.
- **average_order_value** = revenue / orders of the same group (0 when no orders).
- Dates are calendar dates in the business time zone; buckets are local days, ISO weeks (Monday) and
  months, zero-filled, with partial edge buckets flagged — as the dashboard revenue series.

## 2. Compatibility rules (validated on the server, published in the catalogue)

| Visualization | groupBy | Metrics |
|---|---|---|
| `kpi` | `none` | 1–4 |
| `line` | `time` | exactly 1 |
| `bar` | `time`, `store`, `product`, `category` | exactly 1 |
| `pie` | `store`, `product`, `category` | exactly 1, and only an **additive** one: `revenue`, `units`, or `orders` with `store` |
| `table` | any | 1–4 |

Also: `average_order_value` cannot be grouped by `product` or `category` (ambiguous per-order value);
`orders` cannot be shown as a `pie` by `product`/`category` (overlapping shares). No two y-axes ever:
line/bar/pie take one metric.

## 3. Limits

| Limit | Value | Answer when exceeded |
|---|---|---|
| Date range | ≤ `ReportingContext.MAX_RANGE_DAYS` (1,098 days); `day` granularity ≤ 366 days, `week` ≤ 1,098 | `400` naming the limit |
| Result size | ≤ 400 time buckets; groups ≤ `limit` (≤ 50); a table never exceeds 400 rows | `400` on validation; `truncated: true` when more groups exist than `limit` |
| Execution time | SQL: one transaction with `SET LOCAL statement_timeout = '10s'`; Cube: `insight.reports.cube-timeout` deadline | `503` (same messages and `Retry-After` as the report engine, contract `docs/cube-reports-contract.md` §5) |
| Definitions | 200 charts per business; title 1–120 chars, unique per business ignoring case | `409` |

Only allowlisted queries: the backend builds SQL and Cube queries from the validated definition with
fixed templates and bound parameters; no field from the request is ever concatenated into a query.

## 4. API (business from `CurrentBusiness` everywhere; ids looked up by `(id, business_id)`)

| Method & path | Who | Answer |
|---|---|---|
| `GET /api/charts/catalog` | VIEWER+ | `{metrics:[{key,label,unit:"money"|"count",additive}], dimensions:[{key,label,granularities?}], visualizations:[{key,label,groupBy:[…],minMetrics,maxMetrics}], rules:[{visualization?, groupBy?, metric?, allowed:false, reason}], presets:[{key,label}], filters:[{key,label,options?}], limits:{…}, engines:["sql"] or ["sql","cube"], defaultEngine:"sql"}` — enough for the UI to offer only valid combinations; filter `options` list this business's stores, categories (products are searched with the existing `/api/products?q=`). |
| `POST /api/charts/preview` | ADMIN+ | body = definition (unsaved) → `200 ChartResult` |
| `GET /api/charts` | VIEWER+ | `[ChartSummary]` by title |
| `POST /api/charts` | ADMIN+ | body = definition → `201 Chart` (revision 1) |
| `GET /api/charts/{id}` | VIEWER+ | `Chart` |
| `PUT /api/charts/{id}` | ADMIN+ | body = `{definition, expectedRevision}` → `200 Chart` (new revision); stale `expectedRevision` → `409 "This chart was changed by someone else…"` |
| `POST /api/charts/{id}/duplicate` | ADMIN+ | body `{title?}` → `201 Chart` (revision 1, default title "Copy of …", made unique) |
| `DELETE /api/charts/{id}` | ADMIN+ | `204` (revisions deleted too) |
| `GET /api/charts/{id}/revisions` | VIEWER+ | `[{revision, createdBy, createdAt}]` newest first |
| `GET /api/charts/{id}/revisions/{n}` | VIEWER+ | `{revision, definition, createdBy, createdAt}` |
| `GET /api/charts/{id}/data` | VIEWER+ | `ChartResult` for the current revision (`?revision=n` for an older one) |

`Chart` = `{id, title, revision, definition, createdBy, updatedBy, createdAt, updatedAt}`;
`ChartSummary` = `{id, title, visualization, metrics, groupBy, revision, updatedBy, updatedAt}`.

`ChartResult`:

```json
{
  "period": {"from": "2026-07-03", "to": "2026-09-30"},
  "timeZone": "America/New_York", "currency": "USD", "engine": "sql",
  "groupBy": "store", "granularity": null,
  "columns": [{"key": "group", "label": "Store", "type": "dimension"},
              {"key": "revenue", "label": "Revenue", "type": "metric", "unit": "money"}],
  "rows": [{"key": "3", "label": "Boston", "values": {"revenue": 1234.50}, "partial": false}],
  "totals": {"revenue": 98765.40},
  "truncated": false, "totalGroups": 4,
  "generatedAt": "2026-10-02T12:00:00Z"
}
```

- Time rows: `key` = bucket start (ISO date), `label` like the dashboard, `partial` flags partial
  buckets. KPI: one row with `key: "total"`. Totals are over the whole filtered period (not just the
  top groups); for `orders` by product/category the total is distinct orders.
- Validation errors: `400` problem details with `errors: [{field, message}]` (field = JSON path like
  `metrics` or `range.from`) and a readable `detail`.
- Unknown id or another business's id: `404 "Chart not found."`; a filter id of another business:
  `400 "Store 12 is not a store of this business."` (never confirms other businesses' data).
- Writes need `require(Role.ADMIN)` (VIEWER 403, unverified 403, demo/anonymous 401). Charts are not
  part of the public demo (anonymous → 401).
- Responses carry `X-Report-Engine: sql|cube`.

## 5. Storage (integrator: Flyway `V14__create_chart_definitions.sql`, done)

`chart_definitions(id, business_id, title, current_revision, created_by, updated_by, …)` and
`chart_definition_revisions(chart_id, business_id, revision, schema_version, definition jsonb, …)`
with a composite foreign key keeping revisions in their chart's business. The API stores only
definitions that passed validation; reading one that no longer validates (e.g. a deleted store in a
filter) still returns it, and running it answers `400` explaining what to fix.

## 6. Cube

- `engine: "cube"` runs through `CubeClient` with the business from `ReportFilter`/`CurrentBusiness`,
  the same data-version freshness verification, deadline, `must-revalidate` retries and 503 answers as
  `CubeReportEngine` (reuse/extract that code; do not duplicate the rules). Cube stays private.
- Grouping by product may be served without a rollup (document it); every cube/member used must be in
  `security.js` `SCOPE_MEMBER` and covered by `node --test`.
- Cube results must equal SQL results for the same definition (tested).

## 7. Web app

- Nav item **Charts** (`/charts`): list (title, visualization, metrics, grouping, updated by/at), open,
  and for ADMIN+ New, Duplicate, Delete (confirm).
- `/charts/{id}`: the chart rendered (KPI tiles, Recharts line/bar/pie, table), its period, filters,
  engine, revision and a data table view; Edit for ADMIN+; revision history (open an older revision
  read-only).
- `/charts/new`, `/charts/{id}/edit` (builder): title, visualization, metrics, grouping, granularity,
  dates (fixed or rolling preset), filters (stores, categories, products searched), limit, engine
  (only if the catalogue offers Cube). Controls offer only combinations the catalogue allows and
  explain why others are disabled; **Preview** runs `POST /api/charts/preview`; Save creates or saves
  a new revision (409 → explain and offer to reload).
- Viewers see the list and charts, no builder. Follows the `dataviz` skill (one axis, fixed colour
  order, accessible legends/labels, table view). Desktop and 390 px without page overflow.

## 8. Ownership

| Owner | Files |
|---|---|
| **Integrator** | `apps/api/pom.xml`, `db/migration/**` (V14 done), this contract, `README.md`, `infra/**`, merges |
| **backend** agent | `apps/api/**` except the above; `services/analytics/**`; `docs/chart-builder-api.md` |
| **frontend** agent | `apps/web/**`; `docs/frontend-charts.md` |
