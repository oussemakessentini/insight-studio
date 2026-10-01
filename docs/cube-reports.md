# Reports from Cube

How the monthly and category reports (`/api/reports/*`, their `.csv`/`.pdf`, and every saved-report
run and export) are computed by [Cube](analytics.md) instead of SQL, and how the API makes sure Cube's
figures are never older than the data. The binding agreement is
[cube-reports-contract.md](cube-reports-contract.md); this page describes the implementation.

## Choosing the engine

| Property | Environment | Values | Default |
|---|---|---|---|
| `insight.reports.engine` | `REPORTS_ENGINE` | `sql`, `cube` | `sql` |
| `insight.reports.cube-timeout` | `REPORTS_CUBE_TIMEOUT` | ISO duration | `PT10S` |

- `cube` needs the Cube connection of `/api/analytics/summary` (`INSIGHT_CUBE_URL` and
  `CUBEJS_API_SECRET`, see [analytics.md](analytics.md#configuration)). Without it the API does not
  start: `insight.reports.engine=cube (REPORTS_ENGINE) needs Cube: set INSIGHT_CUBE_URL and
  CUBEJS_API_SECRET ...`. A non-positive timeout or an unknown engine also fails startup.
- There is no fallback: with `cube`, a Cube problem is a `503`, never SQL figures.
- Every report response, errors included, carries `X-Report-Engine: sql` or `X-Report-Engine: cube`
  (`/api/reports/**`, `/api/saved-reports/{id}/report`, `.csv`, `.pdf`).

## Code

```
ReportController / SavedReportController
  -> ReportService            every calculation: zero-filled months, partial months, daysCovered,
                              change vs previous month, averages, shares, rounding (ReportCalculations)
       -> ReportEngine        raw totals only: monthly(filter), categories(filter)
            SqlReportEngine   the former ReportQueries (one SQL statement per report)
            CubeReportEngine  Cube REST /load, freshness check, deadline, 503s
                CubeReportQueries   the Cube queries
                analytics.CubeClient.send   one /load call with a fresh 60 s token
```

`ReportEngineConfiguration` creates the engine and adds the header. Because both engines only return
`MonthTotals` / `CategoryBreakdown` and `ReportService` does the rest, the JSON, the CSV bytes and the
PDF are the same for both engines (verified by `CubeReportsIntegrationTest`, below).

## Cube model for the reports

`services/analytics/model/cubes`:

| Cube | Rows | Used for |
|---|---|---|
| `orders` | one per order (receipt with ≥ 1 item), plus one **marker row per business** | monthly revenue, orders, units; the category report's distinct order total |
| `order_categories` (new) | one per (order, category of a product in it), plus **marker rows per business and per catalogue category** | per-category revenue, units and **orders containing the category**; the catalogue |

- `order_categories.count` counts (order, category) rows, which is exactly "orders containing the
  category". Each row belongs to one store and one day, so the count is **additive** and served from a
  daily rollup: no `count_distinct` and nothing approximate. Only products of the store's own business
  count, like the SQL engine (which starts from the business's catalogue).
- **`data_version`** (measure `max`): both cubes' SQL selects
  `(SELECT version FROM report_data_version WHERE id = 1)` next to the data. Being part of the same
  statement, it is read from the same PostgreSQL snapshot as the rows, so a rollup built from that
  SQL (or a query run on it) holds exactly the data of that version.
- **Marker rows** have `sold_at`, `store_id` and the order id `NULL` and no revenue or units; counts
  use `count(id)`/`count(order_id)` so they count nothing. They make sure every build carries its
  version for every business, even one without sales, and list the catalogue (categories never sold
  included) in the same table as the figures. Queries with a date range never see them.
- **Refresh keys** of every cube and rollup:
  `SELECT version, (SELECT MAX(id) FROM businesses) AS last_business FROM report_data_version WHERE id = 1`,
  `every: 1 second`. The version changes with any change to sales, sale items, products or stores
  (Flyway V11 triggers); the newest business id is there because creating a business does not bump the
  version but must add its marker rows. Cube reuses a refresh-key value for the whole `every`
  interval, even for `must-revalidate` requests (`renewalThreshold` = `every` for `sql` keys in
  1.7.46), hence one second.
- Rollups stay **unpartitioned** (see the partitioning bugs in [analytics.md](analytics.md#pre-aggregations)).

### Queries and rollups

Every query sends the business's `timezone`, the token's business (also as an explicit filter; Cube
adds it anyway), the optional store filter and `limit: 10000` (a full answer is treated as invalid).

| Report | Query (`CubeReportQueries`) | Served from |
|---|---|---|
| monthly | `monthly`: revenue, count, units, data_version by `orders.sold_at` month, `dateRange` | rollup `orders.daily_by_store` |
| monthly, period without sales | `monthlyVerified`: same, period in an OR with the marker rows | PostgreSQL (one statement) |
| categories | `categories`: revenue, units, count, data_version by category, `dateRange` | rollup `order_categories.daily_by_store_category` |
| categories, period without sales | `categoriesVerified` | PostgreSQL |
| categories | `catalogue`: data_version by category, no period (marker rows) | rollup `order_categories.daily_by_store_category` |
| categories | `orderTotals`: distinct orders (`orders.count`), data_version, `dateRange` | rollup `orders.daily_by_store` |
| categories, period without orders | `orderTotalsVerified` | PostgreSQL |

The "verified" forms put the period (and store) into `filters` as
`(sold_at inDateRange [from, to] [AND store_id = s]) OR sold_at notSet`. Cube 1.7.46 cannot serve a
filter on the time dimension from a rollup, so these run on PostgreSQL; they are only needed when the
rollup query has no row (no sales in the period), and they are cheap then.

## Freshness: never older than the data

Contract §4: a report must never show figures computed from data older than the
`report_data_version` read at the start of the request, and this must be **verified per request**.

### What Cube 1.7.46 returns, and why the check does not use it

A `/load` answer in production mode (`CUBEJS_DEV_MODE=false`), captured from the test stack
(`apps/api/src/test/resources/cube/monthly-rollup.json`, shortened):

```json
{
  "query": { "measures": ["orders.revenue", "orders.count", "orders.units", "orders.data_version"],
             "timeDimensions": [{ "dimension": "orders.sold_at", "granularity": "month",
                                  "dateRange": ["2026-03-20T00:00:00.000", "2026-06-10T23:59:59.999"] }],
             "timezone": "Europe/Paris", "filters": [ ... ] },
  "lastRefreshTime": "2026-10-01T14:41:54.000Z",
  "usedPreAggregations": {
    "prod_pre_aggregations.orders_daily_by_store": {
      "preAggregationId": "orders.daily_by_store", "lastUpdatedAt": 1790865714000, "type": "rollup" } },
  "dataSource": "default", "dbType": "postgres", "extDbType": "cubestore", "external": true,
  "slowQuery": false,
  "data": [
    { "orders.sold_at.month": "2026-04-01T00:00:00.000", "orders.revenue": "50.1",
      "orders.count": "1", "orders.units": "1", "orders.data_version": "7" },
    { "orders.sold_at.month": "2026-05-01T00:00:00.000", "orders.revenue": "70.4",
      "orders.count": "2", "orders.units": "5", "orders.data_version": "7" }
  ]
}
```

- `refreshKeyValues` and each rollup's `targetTableName` are **not sent** in production mode
  (`publicUsedPreAggregations` in `@cubejs-backend/api-gateway` keeps only `preAggregationId`,
  `lastUpdatedAt`, `type`; the rest only in dev mode or to the Playground).
- Even internally, the `refreshKeyValues` of a served rollup are the refresh-key values Cube read *for
  this request*, not the ones the served table was built with: with the default cache mode Cube serves
  the newest existing build and refreshes in the background (`PreAggregationLoader.loadPreAggregation`).
- `lastUpdatedAt` is the build's start time with second precision and `lastRefreshTime` a timestamp:
  timing, not data versions ("probably fresh" is not acceptable).

### What the check relies on

Only the **rows**: every query asks for `<cube>.data_version`, and the marker rows guarantee that a
verified answer always has one. The value was read by the same SQL statement as the rows that the
answer was computed from, so `data_version >= V` proves the figures include every change up to `V`.

Per request (`CubeReportEngine`):

1. Read `V = SELECT version FROM report_data_version WHERE id = 1` (one deadline of `cube-timeout`
   starts).
2. Run the report's queries. For each answer:
   - all rows with a `data_version` must agree (rows of one answer come from one build or statement;
     otherwise the answer is invalid);
   - no row with a version (empty period) → run the verified form, which always has the marker row.
     It is sent with `"cache": "no-cache"`: for queries that are not served by a rollup, Cube 1.7.46
     re-runs `must-revalidate` queries *without waiting* for fresh refresh-key values
     (`skipRefreshKeyWaitForRenew`), so their cached result, keyed by an old key value, could keep
     coming back (observed: a minute of version 2615 while the data was at 2632);
   - still none → stale (a build older than the business);
   - version `< V` → stale.
   The category report's three answers must also share one version, so rows and totals come from
   the same data.
3. Stale → run the whole report again with `"cache": "must-revalidate"`: Cube re-reads the refresh
   keys and waits for the rebuild (`waitForRenew`), and re-runs cached queries. This is 1.7's
   replacement for the former `renewQuery: true` (which 1.7.46's query schema no longer accepts).
   Within Cube's one-second refresh-key reuse a revalidation may still serve the previous build, so the
   engine asks again (250 ms apart) until the deadline.
4. `"Continue wait"` (Cube still building or querying, sent after its 10 s wait) or a call that ran
   into its read timeout → ask again (250 ms apart) until the deadline. Each HTTP call may only wait for
   what is left of the deadline (the client's 5 s connect and 20 s read timeouts still apply).

Observed with the test stack: after a change, the default cache mode served the old build
(`data_version` 7 while the database was at 9); `must-revalidate` rebuilt the rollup and answered
with 9 (0.4 s for a small table, about 8 s for 1.5 million orders on Docker Desktop).

The background refresh worker also rebuilds on its own schedule, so most requests are answered by the
first query without revalidation. Correctness never depends on its timing.

## Failures (contract §5)

With the `cube` engine, every report endpoint answers a problem detail (RFC 9457) with
`Retry-After`; CSV/PDF requests never return a partial or empty file. Cube's messages are logged
(`CubeClient`, `CubeAnswer`, `CubeReportEngine`), never returned.

| Situation | Status | `detail` | `Retry-After` |
|---|---|---|---|
| Cube unreachable (connection refused), HTTP error (also 401/403: secret mismatch, logged as such), `error` in the body, malformed answer, non-numeric values, rows of several versions, row limit reached | 503 | `Report figures are temporarily unavailable. Try again in a minute.` | 60 |
| Cube still building / still answering "Continue wait" (or not answering a call in time) at the deadline, no stale answer seen | 503 | same | 30 |
| Cube's data still older than the data version at the deadline (including a revalidation still building) | 503 | `Report figures are being updated after recent changes. Try again in a few seconds.` | 5 |

`/api/analytics/summary` is unchanged (`CubeClient.load`: polls up to 30 s, failures are `502`).

## Isolation (contract §6)

Unchanged rules: the token's `businessId` is `ReportFilter.businessId()`, which `ReportingContext`
resolves from `CurrentBusiness` (membership or public demo), never from request input; a store of
another business is a `404` before Cube is called. `services/analytics/security.js` adds the mandatory
`order_categories.business_id` filter (`SCOPE_MEMBER`); `test/security.test.js` covers it and fails
when a cube of the model has no scope member. Marker rows carry their business id like any row.

## Operations

- **Infra** (owned by the integrator): pass `REPORTS_ENGINE` / `REPORTS_CUBE_TIMEOUT` to the API, and
  list every business time zone in `CUBEJS_SCHEDULED_REFRESH_TIMEZONES` (rollups are built per query
  time zone; a zone not listed is built on its first query, which can take longer than the deadline
  and then answers `503` with `Retry-After: 30`).
- The Cube instance the API talks to must be allowed to build pre-aggregations
  (`CUBEJS_REFRESH_WORKER=true` as in compose, or `CUBEJS_PRE_AGGREGATIONS_BUILDER=true`). An API-only
  Cube without it runs with `externalRefresh`, ignores `must-revalidate` for rollups, and reports would
  answer `503` until a separate refresh worker has rebuilt.
- Restart Cube after model changes (production mode does not reload). Right after Cube starts, its
  first refresh pass builds every rollup for every listed zone; in the tests (Docker Desktop) some
  requests hung behind it for over a minute, which the API answers with `503` and `Retry-After: 30`.
- `scripts/reconcile.mjs` also compares the category report (revenue, units, orders per category)
  with `order_categories`.

## Tests

- `report/CubeReportsIntegrationTest`: PostgreSQL 16, Cube Store and Cube v1.7.46 containers on one
  Docker network (`testsupport/CubeStack`, production mode, `services/analytics` mounted read-only, a
  random secret), and two API instances on that database (`sql` and `cube`). Six businesses (New York,
  Paris, Auckland, Kolkata, catalogue only, empty) × 8 windows × every store: the Cube JSON equals the
  SQL JSON byte for byte and both equal hand-written SQL that buckets by local date; CSV bytes and PDF
  text equal; a CSV import through the API, five live changes and a deletion, and a new category are in
  the next report (or `503` "being updated", then fresh); isolation; saved-report runs, CSV and PDF
  equal the JSON totals; a stub Cube that keeps answering version 7 gives `503`/`Retry-After: 5` for
  JSON, CSV and PDF; Cube stopped gives `503`/`Retry-After: 60` for every report.
- `report/CubeReportEngineTest`: the freshness check, revalidation, deadline and failure mapping with
  answers captured from the real Cube (`src/test/resources/cube`).
- `analytics/CubeAnswerTest`: parsing those answers.
- `services/analytics`: `node --test`.

## Limitations

- A line item whose product belongs to another business than its store (not produced by any code
  path) is left out of category rows by both engines, but Cube's distinct order total
  (`orders.count`) would still count its order; the SQL engine would not.
- Category ties are broken by name in the database's collation: the Cube engine sorts its rows with one
  small PostgreSQL statement (`jsonb_to_recordset ... ORDER BY revenue DESC, category`), exactly like
  the SQL engine's `ORDER BY`.
- A period without sales is answered from PostgreSQL (the verified form) rather than a rollup.
- Each rollup rebuild reads all data (no partitions); fine at the current volume.
