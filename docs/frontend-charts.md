# Web app: chart builder

How `apps/web` implements `docs/chart-builder-contract.md` (§7). The server is authoritative for
every check; the UI offers only what the catalogue allows, mirrors the simple checks so mistakes show
while editing, and shows the server's message whenever it refuses.

## Routes

| Path | Route | Page |
|---|---|---|
| `/charts` | `charts` | `pages/ChartsPage.tsx`: the list |
| `/charts/new` | `chartNew` | `pages/ChartBuilderPage.tsx` (`chartId = null`) |
| `/charts/{id}` | `chart` | `pages/ChartPage.tsx` (keyed by id); `?revision=n` shows revision *n* read-only |
| `/charts/{id}/edit` | `chartEdit` | `pages/ChartBuilderPage.tsx` |

A new sidebar item **Charts** (after Reports, `ChartIcon`) keeps all four routes active
(`sectionOf`). `lib/router.ts` gained `useSearch()`, which re-renders on query-only navigations, so
links between revisions of the same chart update the page.

## API client

- `api/charts.ts`: every type of contract §1/§4 (`ChartDefinition`, `Chart`, `ChartSummary`,
  `ChartRevision(Summary)`, `ChartCatalog` with its metrics/dimensions/visualizations/rules/presets/
  filters/limits/engines, `ChartResult`) and `chartsApi`
  `catalog / preview / list / get / create / update(id, definition, expectedRevision) / duplicate /
  remove / revisions / revision(id, n) / data(id, revision?)`.
- `api/client.ts`: `ApiError.fieldErrors` carries a 400 problem detail's `errors: [{field, message}]`
  (empty for every other error, so existing callers are unchanged).
- Products are searched with the existing `GET /api/products?q=…&sort=name&direction=asc&size=8`;
  names of products already in a definition come from `GET /api/products/{id}`
  (`hooks/useProductNames.ts`; a product that can't be read shows as "Product {id}").

## Rendering (`components/charts/ChartView.tsx`)

`ChartPanel` renders a `ChartResult` for the definition's visualization, following the `dataviz`
skill:

| Visualization | Form |
|---|---|
| `kpi` | 1–4 tiles (label, value, period); the grid sizes itself to the panel, so tiles never clip in the builder's narrow preview |
| `line` | one 2px line with a 10% area wash, crosshair tooltip; partial edge buckets drawn dashed with a caption |
| `bar` by time | columns ≤ 24px with 4px rounded ends; partial buckets drawn lighter with a caption |
| `bar` by store/product/category | horizontal bars (names read left to right) with the value at each tip |
| `pie` | a donut of at most five slices plus a grey "Other" (the total minus the slices shown, which also covers groups beyond `limit`); a legend lists every value and share |
| `table` | the data table |

- One metric per line/bar/pie, one axis, never two scales (the catalogue enforces it too).
- Money is formatted in the result's `currency`, counts as whole numbers; axes use compact values.
- Every chart has a **Chart / Table** toggle; the table lists every row, marks partial buckets
  ("Partial") and ends with a sticky **Total** row (over all groups, "Total (all groups)" when
  truncated).
- Notes under the chart: "Showing the top 10 of 34 products by revenue. Totals cover all of them."
  when `truncated`, and that orders by product/category overlap (contract §1).
- Colours: `lib/charts.ts` `CATEGORICAL` is the skill's validated order (blue, orange, aqua, yellow,
  magenta) checked with `validate_palette.js` against the app's white panel (`#ffffff`): all checks
  pass for adjacent pairs, including the pie's wrap-around pair (blue/magenta); aqua, yellow and
  magenta are below 3:1 contrast, so the pie legend always shows values and every chart has a table
  view. Single series use slot 1 (`#2a78d6`, the dashboard's blue). Pie slices are coloured in value
  order (the largest is always blue). The app has no dark theme, so there is no dark palette.
- States: skeleton while loading (the previous result stays dimmed while refetching), "No sales
  match this chart's dates and filters." when every value is zero (KPI tiles still show their zeros),
  and errors with the server's message and **Try again** (503 "busy" answers from either engine). A
  400 while running a saved chart (e.g. a deleted store in its filters) adds "Edit the chart" for
  admins and "An owner or admin needs to update this chart" for viewers.

## Pages

- **List**: title (link), chart type, metrics, grouping, updated (business time zone, "by … ·
  revision n"); on phones the hidden columns move under the title. ADMIN+: **New chart**, and per row
  **Duplicate** (the server names the copy; a notice links to it) and **Delete** with an inline
  confirmation. Empty state explains how to build one (viewers: that owners and admins do).
- **Chart**: title = definition title; details line: chart type · range ("Last 90 days (rolling) ·
  Jul 3 – Sep 30, 2026", the period from the result) · engine · revision. Then the chart, **About this
  chart** (metrics, grouping with granularity or top N, dates with the rolling explanation, stores,
  categories, products by name, last saved/created by) and **Revisions** (newest first; the current
  one tagged, each older one a link to `?revision=n`). ADMIN+: **Edit**, **Duplicate** (opens the
  copy), **Delete** (inline confirmation, then back to the list).
- **Revision view** (`?revision=n`, n ≠ current): a note "You're looking at revision n saved … by …
  It is read-only; the chart now uses revision m. See the current version", the revision's definition
  and `GET /data?revision=n`; no Edit/Duplicate/Delete. A missing revision says so.
- `404` on the chart: "Chart not found" with a link to the list.

## Builder (`pages/ChartBuilderPage.tsx`, `components/charts/ProductPicker.tsx`)

Loads `GET /api/charts/catalog` (and the chart when editing), then:

- **Title** (1–120, checked on blur and on save; not needed to preview).
- **Visualization**: one card per catalogue visualization with its metric range. Changing it (or
  the grouping) runs `reconcile`, which moves the grouping to the first allowed one, drops metrics
  the new combination refuses or exceeds, and says what changed ("Grouping changed to store; Average
  order value removed to fit this chart.").
- **Group by**: every catalogue dimension; ones the visualization refuses are disabled with the
  reason under them (from a matching catalogue rule, else "Not with Line: it groups by time.").
- **Each point is a** day/week/month (catalogue granularities) when grouped by time.
- **Metric(s)**: radios when the visualization takes one metric, checkboxes otherwise. Refused
  metrics are disabled with the catalogue rule's reason (e.g. average order value by product, orders
  in a pie by product, non-additive metrics in a pie), and at the maximum the rest say "KPI tiles:
  at most 4 metrics. Clear one to choose this." A rule applies when every field it names matches.
- **Groups shown** (1–`limits.maxLimit`, default 10) when grouped by store/product/category.
- **Dates**: a rolling period (catalogue presets; "Rolling periods are recalculated from today's
  date in {time zone} every time the chart runs.") or fixed dates (≤ 1,098 days; day buckets ≤ 366,
  week buckets ≤ 1,098). Switching back and forth keeps each choice.
- **Filters**: stores and categories as checklists from the catalogue's options (nothing ticked =
  all; at most 50; a saved value no longer offered stays listed so it can be removed); products
  searched and shown as removable chips.
- **Engine** only when the catalogue lists `cube`.
- **Preview** runs `POST /api/charts/preview` (with a placeholder title if none yet) and shows the
  result beside the form (below it on narrow screens, scrolled into view). When the settings change
  afterwards the preview says so.
- **Save chart** (`POST`) / **Save new revision** (`PUT` with `expectedRevision` = the revision the
  form started from), then opens the chart.
- Server errors: a 400's `errors` are shown next to their fields (paths like `range.to`,
  `definition.metrics` or `filters.storeIds[2]` are normalised); errors for paths without a visible
  field are listed in the form alert (on save) or in the preview panel (on preview). Editing clears
  them.
- `409` on save: the builder re-reads the chart. If its revision moved on, it explains "This chart
  was changed by someone else (who, when) … now at revision 4; you started from revision 1" with
  **Load the latest version** (replaces the form and the expected revision) and **Open it in a new
  tab**. Otherwise (a taken title, the 200-chart limit) the server's message is shown, next to the
  title when it is about the title.

## Permissions

```ts
chartPermissions(access) = {
  available: access.role !== 'DEMO',
  canManage: (role OWNER | ADMIN) && access.emailVerified && !access.readOnly,
  needsVerification: (role OWNER | ADMIN) && !access.emailVerified,
}
```

| Who | Charts |
|---|---|
| Public demo | Every `/charts*` page shows "Charts need an account" with Sign in (the API answers 401) |
| VIEWER | list, open, run, revisions, table view; `/charts/new` and `/edit` show "You don't have access" |
| OWNER / ADMIN, unverified | as VIEWER, plus a note to verify (with "Send a new link"); `/new` and `/edit` show "Verify your email to continue" |
| OWNER / ADMIN, verified | everything |

## For the integrator to verify against the real backend

1. Catalogue `filters[].options` shape: the UI accepts `{value, label}`, `{id, name}` or plain
   strings; filter keys are `storeIds`, `categories`, `productIds`. Without store options it falls
   back to the context's stores.
2. Catalogue `limits` key names: read as `maxLimit`/`defaultLimit`/`maxRangeDays`/
   `maxDayRangeDays`/`maxWeekRangeDays`/`maxFilterValues`/`titleMaxLength` (several aliases), falling
   back to the contract values (50, 10, 1,098, 366, 1,098, 50, 120).
3. `rules[]` entries name only the fields they constrain; a rule with `visualization` + `groupBy`
   (no metric) disables that grouping, with `metric` (± others) disables that metric.
4. `ChartResult.columns`: one `{type: "dimension"}` column (absent or present for KPI) and metric
   columns in definition order with `unit`; `rows[].values` keyed by metric. Time rows' `key` is the
   bucket start ISO date (labels are formatted client-side like the dashboard).
5. 400 problem details carry `errors: [{field, message}]`; on `PUT` the field may be prefixed with
   `definition.`. 409 on a stale `expectedRevision` and on a duplicate title are told apart by
   re-reading the chart's revision.
6. `GET /api/charts/{id}/data?revision=n` runs revision n; `GET /revisions/{n}` 404s for a missing
   revision.
7. `POST /api/charts/{id}/duplicate` accepts `{}` (no title) and returns the new `Chart`.
8. `createdBy`/`updatedBy` may be `null`; instants are ISO UTC.
9. `GET /api/products/{id}` without `from`/`to` returns `{product: {name, …}}` (used for names only).
