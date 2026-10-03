# Chart builder (API)

What the backend implements for [chart-builder-contract.md](chart-builder-contract.md). The contract
is binding; this page describes the code, the exact JSON, and the details the contract leaves open.

## Endpoints

| Method & path | Who | Answer |
|---|---|---|
| `GET /api/charts/catalog` | member (VIEWER+) | `200` catalogue (below) |
| `POST /api/charts/preview` | `require(Role.ADMIN)` | `200 ChartResult` for an unsaved definition (title may be empty) |
| `GET /api/charts` | member | `200 [ChartSummary]` by `lower(title)`, then id |
| `POST /api/charts` | `require(Role.ADMIN)` | `201 Chart` (revision 1) |
| `GET /api/charts/{id}` | member | `200 Chart` |
| `PUT /api/charts/{id}` | `require(Role.ADMIN)` | body `{definition, expectedRevision}` → `200 Chart` (next revision) |
| `POST /api/charts/{id}/duplicate` | `require(Role.ADMIN)` | body `{title?}` or none → `201 Chart` (revision 1) |
| `DELETE /api/charts/{id}` | `require(Role.ADMIN)` | `204` (revisions deleted by the foreign key's cascade) |
| `GET /api/charts/{id}/revisions` | member | `200 [{revision, createdBy, createdAt}]`, newest first |
| `GET /api/charts/{id}/revisions/{n}` | member | `200 {revision, definition, createdBy, createdAt}` |
| `GET /api/charts/{id}/data` | member | `200 ChartResult` of the current revision; `?revision=n` for an older one |

- The business is always `CurrentBusiness` (membership, `X-Business-Id` as selector); charts and
  revisions are looked up by `(id, business_id)`, so another business's id is `404 "Chart not found."`
  on every endpoint. `/api/charts/**` is not demo-readable: anonymous callers get `401`, also with the
  public demo on.
- Order of checks for writes: membership and role, then (`PUT`, duplicate, `DELETE`) the chart's
  existence, then the body.
- `preview` and `data` responses carry `X-Report-Engine: sql|cube` (set before the engine runs, so
  its `503`s carry it too).

## Definitions

Exactly the contract's model. The API **normalizes** what it stores and returns: every field present,
in the contract's order, title trimmed, `limit` defaulted to 10 for store/product/category and `null`
otherwise, `engine` defaulted to `sql`, `filters` lists empty when absent, `schemaVersion` 1:

```json
{"schemaVersion": 1, "title": "Revenue by store", "visualization": "bar", "metrics": ["revenue"],
 "groupBy": "store", "granularity": null, "range": {"type": "relative", "preset": "last_90_days"},
 "filters": {"storeIds": [], "categories": [], "productIds": []}, "limit": 10, "engine": "sql"}
```

- `range` is a saved-report range: `{"type": "fixed", "from", "to"}` (inclusive ISO dates) or
  `{"type": "relative", "preset"}` with the presets of `savedreport.RelativePreset`, resolved against
  today in the business time zone each time the chart runs (`PeriodResolver`). For `relative`,
  `from`/`to` are ignored; for `fixed`, `preset` is ignored.
- Fields the contract does not define are refused (`"Unknown field 'layout'."`), so a definition never
  carries layout or anything else. Within `range` and `filters` too.
- `categories` are exact catalogue names (case-sensitive, like the category report).

### Metric definitions

As the report API: revenue = Σ `quantity × unit_price` (prices charged), an order = a receipt with at
least one line item, units = Σ quantity, average order value = revenue / orders of the same group
(0 without orders, rounded half-up to cents). Dates are local dates in the business time zone.

**Filters**: `storeIds` selects receipts of those stores; `categories` and `productIds` select **line
items** (both may be combined: items of those products that are also in those categories). With an
item filter, revenue and units count only the matching items and an order is a receipt containing at
least one matching item. Grouped by product or category, a receipt counts once in every group it has
items in; the totals count it once.

### Groups and rows

| groupBy | Rows |
|---|---|
| `none` | one row, `key: "total"`, `label: "Total"` |
| `time` | every bucket overlapping the period, zero-filled, `key` = first day (ISO), `partial` when the bucket is not entirely inside the period (same buckets as the dashboard revenue series). Labels as the dashboard: `"Mon, Jul 6, 2026"`, `"Week of Jul 6, 2026"`, `"July 2026"` |
| `store` | every store of the business allowed by `storeIds` (zeros included); `key` = store id, `label` = name |
| `category` | every catalogue category allowed by `categories` and, with `productIds`, the categories of those products (zeros included); `key` = `label` = name |
| `product` | the products with sales matching the filters; `key` = product id, `label` = name |

Store, product and category rows are ranked by the **first metric** (highest first), ties by label
ignoring case, then by label, then key; then cut to `limit` (`truncated: true` when more groups exist,
`totalGroups` = groups before the cut). `totals` are over the whole filtered period (every group);
`totals.orders` is distinct orders.

## JSON shapes

`Chart`:

```json
{"id": 12, "title": "Revenue by store", "revision": 3, "definition": { "...normalized definition..." },
 "createdBy": "Ada", "updatedBy": "Grace", "createdAt": "2026-10-02T12:00:00Z", "updatedAt": "2026-10-02T12:30:00Z"}
```

`createdBy` is the chart creator's display name; `updatedBy` whoever saved the current revision.

`ChartSummary`:

```json
{"id": 12, "title": "Revenue by store", "visualization": "bar", "metrics": ["revenue"], "groupBy": "store",
 "revision": 3, "updatedBy": "Grace", "updatedAt": "2026-10-02T12:30:00Z"}
```

Revisions: list `[{"revision": 3, "createdBy": "Grace", "createdAt": "..."}]` (no `definition` key);
detail `{"revision": 1, "definition": {...}, "createdBy": "Ada", "createdAt": "..."}`.

`ChartResult` (time grouping shown):

```json
{"period": {"from": "2026-03-25", "to": "2026-04-05"}, "timeZone": "Europe/Paris", "currency": "EUR",
 "engine": "sql", "groupBy": "time", "granularity": "week",
 "columns": [{"key": "group", "label": "Week", "type": "dimension"},
             {"key": "revenue", "label": "Revenue", "type": "metric", "unit": "money"},
             {"key": "orders", "label": "Orders", "type": "metric", "unit": "count"}],
 "rows": [{"key": "2026-03-23", "label": "Week of Mar 23, 2026", "values": {"revenue": 30.00, "orders": 3}, "partial": true},
          {"key": "2026-03-30", "label": "Week of Mar 30, 2026", "values": {"revenue": 30.00, "orders": 3}, "partial": false}],
 "totals": {"revenue": 60.00, "orders": 6},
 "truncated": false, "totalGroups": 2, "generatedAt": "2026-10-02T12:00:00.123Z"}
```

- The `group` column is absent for `groupBy: "none"`; its label is `Day`/`Week`/`Month`, `Store`,
  `Product` or `Category`. Metric columns follow the definition's order; `values` and `totals` keep it.
- Money values have 2 decimals; counts are integers. `granularity` is `null` unless grouped by time.
- Metric labels: `Revenue`, `Orders`, `Units sold`, `Average order value`.

### Catalogue

```json
{"metrics": [{"key": "revenue", "label": "Revenue", "unit": "money", "additive": true},
             {"key": "orders", "label": "Orders", "unit": "count", "additive": false},
             {"key": "units", "label": "Units sold", "unit": "count", "additive": true},
             {"key": "average_order_value", "label": "Average order value", "unit": "money", "additive": false}],
 "dimensions": [{"key": "none", "label": "Total"},
                {"key": "time", "label": "Time", "granularities": ["day", "week", "month"]},
                {"key": "store", "label": "Store"}, {"key": "product", "label": "Product"},
                {"key": "category", "label": "Category"}],
 "visualizations": [{"key": "kpi", "label": "KPI tiles", "groupBy": ["none"], "minMetrics": 1, "maxMetrics": 4},
                    {"key": "line", "label": "Line chart", "groupBy": ["time"], "minMetrics": 1, "maxMetrics": 1},
                    {"key": "bar", "label": "Bar chart", "groupBy": ["time", "store", "product", "category"], "minMetrics": 1, "maxMetrics": 1},
                    {"key": "pie", "label": "Pie chart", "groupBy": ["store", "product", "category"], "minMetrics": 1, "maxMetrics": 1},
                    {"key": "table", "label": "Table", "groupBy": ["none", "time", "store", "product", "category"], "minMetrics": 1, "maxMetrics": 4}],
 "rules": [{"groupBy": "product", "metric": "average_order_value", "allowed": false, "reason": "Average order value cannot be grouped by product or category: ..."},
           {"groupBy": "category", "metric": "average_order_value", "allowed": false, "reason": "..."},
           {"visualization": "pie", "metric": "average_order_value", "allowed": false, "reason": "A pie chart shows shares of a total, ..."},
           {"visualization": "pie", "groupBy": "product", "metric": "orders", "allowed": false, "reason": "Orders cannot be shown as a pie by product or category: ..."},
           {"visualization": "pie", "groupBy": "category", "metric": "orders", "allowed": false, "reason": "..."}],
 "presets": [{"key": "last_7_days", "label": "Last 7 days"}, "..."],
 "filters": [{"key": "storeIds", "label": "Stores", "options": [{"value": 3, "label": "Boston"}]},
             {"key": "categories", "label": "Categories", "options": [{"value": "Tops", "label": "Tops"}]},
             {"key": "productIds", "label": "Products", "search": "/api/products?q="}],
 "limits": {"maxRangeDays": 1098, "maxRangeDaysByGranularity": {"day": 366, "week": 1098, "month": 1098},
            "maxTimeBuckets": 400, "minLimit": 1, "maxLimit": 50, "defaultLimit": 10, "maxFilterValues": 50,
            "maxCharts": 200, "maxTitleLength": 120, "schemaVersion": 1},
 "engines": ["sql"], "defaultEngine": "sql"}
```

- A combination is valid when the visualization lists the grouping, the metric count is within
  `minMetrics..maxMetrics`, and no `rules` entry matches (an absent `visualization`/`groupBy`/`metric`
  matches anything). This is exactly what the server checks: the catalogue is generated from the
  same Java definitions (`ChartVisualization`, `ChartMetric`, `ChartGroupBy`, `ChartRules`), and
  `ChartValidatorTest` checks every combination against the published catalogue.
- `additive`: groups add up to the total for every grouping. Orders add up by time and store only.
- `options` are the business's stores (by name) and catalogue categories; products are searched with
  the existing `GET /api/products?q=`.
- `engines` contains `cube` only when this instance has a Cube connection.

## Errors (RFC 9457 problem details)

| Status | When | Body |
|---|---|---|
| 400 | invalid definition (`POST`, `preview`, `PUT`, duplicate, or a stored definition that no longer validates when run) | `detail` (the messages joined) and `errors: [{field, message}]` |
| 400 | malformed JSON, no body, non-numeric id or `revision` | plain problem detail |
| 401 | not signed in (also for the public demo) | |
| 403 | VIEWER (`You need the ADMIN role for this.`), unverified email, missing/invalid CSRF header | |
| 404 | `Chart not found.` (unknown or another business's id, every endpoint); `Revision 3 of this chart was not found.`; `Business not found.` (an `X-Business-Id` the caller is not a member of) | |
| 409 | `A chart titled '<title>' already exists.` (same title ignoring case, checked first; the unique index decides races); `This chart was changed by someone else since you opened it. Reload it to see the latest version, then make your changes again.` (stale `expectedRevision`); `A business can have at most 200 charts. Delete one before adding another.` | |
| 503 | figures unavailable (below) | `detail` + `Retry-After` |

`errors[].field` is a JSON path **relative to the definition** (also for `PUT`, where the definition
is nested): `title`, `visualization`, `metrics`, `metrics[1]`, `groupBy`, `granularity`, `range`,
`range.type`, `range.preset`, `range.from`, `range.to`, `filters`, `filters.storeIds`,
`filters.storeIds[0]`, `filters.categories[0]`, `filters.productIds[2]`, `limit`, `engine`,
`schemaVersion`, an unknown field's name; `expectedRevision` for `PUT`; `title` for duplicate. All
problems are reported at once. Examples of messages:

- `Enter a title for the chart.` · `The title may be at most 120 characters.`
- `'visualization' must be one of kpi, line, bar, pie, table.` · `Bar chart: choose exactly 1 metric.` ·
  `Table: choose 1 to 4 metrics.` · `'metrics[1]' must be one of revenue, orders, units, average_order_value.` ·
  `'revenue' is listed twice.`
- `Line chart: groupBy must be time.` · `'granularity' must be day, week or month when grouping by time.` ·
  `'granularity' is only used when grouping by time; set it to null.`
- a `rules` reason, on `metrics[i]`
- `'from' (2026-07-02) must be on or before 'to' (2026-07-01).` · `The date range may cover at most 1098 days.` ·
  `Daily buckets cover at most 366 days: choose larger buckets or a shorter period.`
- `'limit' must be a whole number from 1 to 50.` · `'limit' is only used when grouping by store, product or category; set it to null.`
- `Store 12 is not a store of this business.` · `Product 5 is not a product of this business.` ·
  `'tops' is not a category of this business.` · `At most 50 stores can be selected.` · `Store 3 is listed twice.`
- `Cube is not configured on this server; use the sql engine.`

## Revisions and concurrency

- `chart_definitions` (Flyway V14) holds the title, `current_revision`, creator and last editor;
  every save inserts an immutable `chart_definition_revisions` row with the normalized definition.
- `PUT` checks `expectedRevision` against the current revision (409 when different), validates, then
  `UPDATE ... SET current_revision = current_revision + 1 ... WHERE current_revision = :expected`; zero
  rows (someone saved in between) is the same 409. Nothing is written on a 409.
- Duplicate copies the **current** revision's definition with the new title, re-validates it, and
  creates revision 1. Without a title: `Copy of <title>`, then `Copy of <title> (2)`, … (shortened to
  fit 120 characters); a given title that is taken is a 409.
- Creating (and duplicating) locks the business row (`FOR NO KEY UPDATE`) so the 200-chart limit holds
  under concurrent requests.
- Old revisions stay readable and runnable (`?revision=n`) until the chart is deleted.

## Engines

`ChartService` resolves the period, asks the definition's engine for raw figures (revenue, orders,
units per group and in total) and `ChartResults` builds the result, so both engines give the same JSON.

### SQL (`SqlChartEngine`)

- One fixed statement per grouping (`TEMPLATES`), plus constant conditions for the filters in use
  (`AND s.store_id IN (:storeIds)`, `AND si.product_id IN (:productIds)`, `AND p.category IN (:categories)`).
  Every value — business, start/end instants, time zone, bucket unit (`date_trunc(:unit, ...)`), ids,
  names — is a bound parameter; nothing from the request is ever in the SQL text (`SqlChartEngineTest`).
- Groups and the total come from one statement (`GROUP BY GROUPING SETS ((group_key), ())`), so
  `totals.orders` is distinct across groups.
- It runs in the request's read-only transaction after `SELECT set_config('statement_timeout', :ms, true)`
  (equivalent to `SET LOCAL statement_timeout`), `insight.charts.statement-timeout`
  (`CHARTS_STATEMENT_TIMEOUT`, default `PT10S`). A statement cancelled by it (SQLState `57014`) is
  `503 "Report figures are temporarily unavailable. Try again in a minute."` with `Retry-After: 30`
  (like Cube still working at its deadline). The engine refuses to run outside a transaction.

### Cube (`CubeChartEngine`, `"engine": "cube"`)

Offered when the API has a Cube connection (`INSIGHT_CUBE_URL` + secret, as `/api/analytics/summary`),
whatever `insight.reports.engine` says. Saving or previewing a cube chart without it is a 400 on
`engine`; running a stored one without it is the same 400.

- Same rules as the Cube report engine, through the shared `report.CubeFreshness` (extracted from
  `CubeReportEngine`, which now uses it too): read `report_data_version`, require every answer's
  `data_version` to be at least that, revalidate with `"cache": "must-revalidate"` until the
  `insight.reports.cube-timeout` deadline, one data version per chart, and the same 503 answers and
  `Retry-After` (60 unavailable, 30 still building, 5 being updated). See
  [cube-reports.md](cube-reports.md).
- Queries (`CubeChartQueries`), all exact:

| Chart | Cube | Served from |
|---|---|---|
| none / time / store, no item filter | `orders`: revenue, count, units by `sold_at` granularity or `store_id` | rollup `orders.daily_by_store` (verified form on PostgreSQL when the period has no sales) |
| none / time / store, with `categories`/`productIds` | `order_products.distinct_orders` (COUNT DISTINCT), revenue, units | PostgreSQL (verified form only) |
| product | `order_products`: revenue, `count` (line items = orders containing the product, since `sale_items` has a unique `(sale_id, product_id)`), units | rollup `order_products.daily_by_store_product` |
| category, no product filter | `order_categories`: revenue, count (orders containing the category), units | rollup `order_categories.daily_by_store_category` |
| category, with `productIds` | `order_products.distinct_orders` by category | PostgreSQL |
| product / category totals: distinct orders | `orders.count` (no item filter, rollup) or `order_products.distinct_orders` (PostgreSQL) | |

- New cube `order_products` (services/analytics/model/cubes/order_products.yml): one row per line
  item with business, store, time, product, category, units, revenue and `data_version`, plus one
  marker row per business; `refresh_key` on `report_data_version` every second like the other cubes;
  daily rollup by business, store, product and category (unpartitioned). `security.js` scopes it on
  `order_products.business_id` (`SCOPE_MEMBER`); `test/security.test.js` covers it.
- Queries that need distinct orders across products or categories are not served by a rollup
  (`count_distinct` is not additive) and run as one statement on PostgreSQL through Cube; the API sends
  them with `"cache": "no-cache"` in the verified form, so they always carry their version.

## Tests

- `chart/ChartValidatorTest`: every visualization × grouping × ordered metric selection is accepted
  exactly when the catalogue allows it; every field rule answers with its field; range and bucket limits.
- `chart/SqlChartEngineTest`: only the fixed statements can be produced; hostile filter values never
  reach the SQL text.
- `chart/ChartMetricsIntegrationTest`: 210 definitions (7 groupings × 6 filter sets × 5 windows, a
  Europe/Paris business with both DST changes and month ends) against an independent SQL that buckets
  by local date; hand-computed DST/midnight cases; top-N and totals; KPIs; monthly charts = monthly
  report, category charts = category report (rows and totals, with and without a store).
- `chart/ChartApiIntegrationTest`: CRUD, revisions (old revision readable and runnable, stale
  `expectedRevision` 409, duplicate, delete), title uniqueness, the 200-chart limit, field-level 400s,
  filter ids of another business, the full permission matrix (VIEWER/ADMIN/OWNER/unverified/anonymous,
  CSRF) and cross-business 404s on every endpoint, `X-Business-Id` tricks.
- `chart/ChartStatementTimeoutIntegrationTest`: another connection locks `sale_items`; an API instance
  with a one-second timeout answers 503 (no test hook in the API).
- `chart/ChartPublicDemoIntegrationTest`: charts stay 401 for the public demo.
- `report/CubeReportsIntegrationTest` (real Cube and Cube Store): cube charts equal sql charts for every
  grouping and filter set in two time zones; fresh after live changes; a stale stub Cube → 503
  "being updated"; Cube stopped → 503 "temporarily unavailable" while sql charts keep working.

## Limitations

- Product groups list only products with sales (the catalogue can be large); stores and categories
  include zero groups.
- Ties in the ranking are broken by label ignoring case in Java (`String.CASE_INSENSITIVE_ORDER`), not
  by the database collation; both engines use the same rule.
- A Cube answer is limited to 10,000 rows (as for reports): a product chart over a business with more
  products sold in the period answers 503.
- Filtered Cube charts that need distinct orders run on PostgreSQL through Cube (no rollup); fine at the
  current volume, slower than rollups on large data.
- Group labels are read from PostgreSQL at request time, so a product renamed after the period still
  shows its current name.
