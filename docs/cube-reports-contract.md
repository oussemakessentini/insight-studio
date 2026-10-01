# Cube-backed reports: contract

Binding agreement between the integrator and the **backend** agent for moving the monthly and
category report calculations to Cube. Change it only through the integrator.

## 1. Goal and non-goals

- The monthly and category reports (`/api/reports/monthly`, `/api/reports/categories`, their `.csv`
  and `.pdf`, and every saved-report run and export) can be computed by **Cube** instead of SQL.
- The existing SQL implementation stays, selectable by configuration, until the migration is done.
- Non-goals: the dashboard, products, sales and store pages stay on SQL; no new UI features.

## 2. Engine selection

| Property | Env | Values | Default |
|---|---|---|---|
| `insight.reports.engine` | `REPORTS_ENGINE` | `sql`, `cube` | `sql` |
| `insight.reports.cube-timeout` | `REPORTS_CUBE_TIMEOUT` | duration | `PT10S` |

- `cube` requires a configured Cube (`INSIGHT_CUBE_URL` + secret, as for `/api/analytics/summary`);
  startup **fails** with a clear message otherwise. There is no automatic fallback from one engine to
  the other at runtime.
- Every report response (JSON, CSV, PDF, saved runs) carries `X-Report-Engine: sql|cube`.

## 3. What must not change

`ReportService` keeps every calculation it does today (month buckets with zero-fill, partial months,
`daysCovered`, change vs previous month, averages, shares, rounding via `ReportCalculations`). Only
the raw per-period totals move behind an engine interface, e.g.

```java
interface ReportEngine {           // name and shape up to the agent
    List<MonthTotals> monthly(ReportFilter filter);
    CategoryBreakdown categories(ReportFilter filter);
}
```

with `SqlReportEngine` (today's `ReportQueries`) and `CubeReportEngine`. Therefore:

- JSON response shapes, CSV and PDF output are **byte-for-byte the same** for the same data whichever
  engine runs (apart from the `X-Report-Engine` header).
- Revenue is `quantity × unit price charged` (historical prices), never `list_price`.
- An order is a receipt with at least one item (`ReportSql.HAS_ITEMS`; Cube's `orders` cube).
- Dates are calendar dates in the **business time zone**; month buckets and the `from`/`to` boundaries
  (inclusive local days) are evaluated in that zone (Cube query `timezone`).
- Category rows include every catalogue category of the business (zeros included), ordered as today
  (revenue desc, then name); per-category `orders` are distinct orders containing the category; totals
  `orders` are distinct orders overall.
- Store filter and validation (`404` for another business's store) behave as today.

## 4. Freshness (never stale)

- Flyway **V11** (integrator, done) adds `report_data_version(id = 1, version)`, bumped by
  statement-level triggers in the same transaction as **any** insert/update/delete/truncate on
  `sales`, `sale_items`, `products` or `stores`. It changes exactly when report data changes, and only
  once the change commits.
- Cube: every cube and rollup used by reports gets `refresh_key.sql: SELECT version FROM
  report_data_version` with a short check interval, so changes are picked up quickly.
- **Guarantee:** the API must never return figures computed from data older than the version it read
  from `report_data_version` at the start of the request. It must *verify* this per request (for
  example from the refresh-key values / pre-aggregation metadata in Cube's `/load` response, or with an
  equivalent marker), retry with `renewQuery: true` while within the deadline, and otherwise answer
  `503` (below). "Probably fresh" based on timing is not acceptable. Document exactly what Cube 1.7.46
  returns and what the check relies on.
- After an import (or any data change) commits, the next report either includes the change or answers
  `503`; it never silently shows the old figures.

## 5. Failures and timeouts (never stale, never partial)

With the `cube` engine, every report endpoint (JSON, CSV, PDF, saved runs):

| Situation | Answer |
|---|---|
| Cube unreachable, HTTP error, invalid response | `503`, `"Report figures are temporarily unavailable. Try again in a minute."`, `Retry-After: 60` |
| Cube still building after `cube-timeout` ("Continue wait") | `503`, same message, `Retry-After: 30` |
| Cube's data is older than the current data version after `cube-timeout` | `503`, `"Report figures are being updated after recent changes. Try again in a few seconds."`, `Retry-After: 5` |

- Problem details (RFC 9457); Cube's internals are logged, never returned. A CSV/PDF request never
  returns a partial or empty file in these cases.
- One overall deadline per request (`cube-timeout`) covering all Cube calls the report needs; the
  existing per-call connect/read timeouts stay.
- Unchanged: `/api/analytics/summary` keeps its current behaviour.

## 6. Isolation (unchanged rules, re-verified)

- Cube stays private (bound to `127.0.0.1`, reached only by the API). The business in Cube's token is
  the one resolved by `CurrentBusiness` from the caller's membership (`ReportFilter.businessId()`),
  never from request input; `services/analytics/security.js` keeps adding the mandatory business
  filter. New cubes or members must be added to `SCOPE_MEMBER` and covered by `test/security.test.js`.

## 7. Reconciliation (required tests)

Integration tests with **real Cube and Cube Store containers** (Testcontainers, images
`cubejs/cube:v1.7.46` and `cubejs/cubestore:v1.7.46`, production mode, the repo's
`services/analytics` model mounted) comparing, for every case, the Cube engine's report JSON with the
SQL engine's **and** with an independent hand-written SQL query in the test (not `ReportQueries`):

- at least three businesses in different time zones (e.g. `America/New_York`, `Europe/Paris`,
  `Pacific/Auckland` or `Asia/Kolkata`), with sales near local midnight, month ends and a DST change;
- store filter vs all stores; empty periods (no sales, a business without sales); partial months;
- price changes (same product sold at different prices), receipts without items (excluded);
- data loaded through the CSV import API, checked **immediately** after the import commits (freshness),
  and a catalog change (new category) showing up in the category report;
- business isolation: a business never sees another's figures through Cube;
- failure modes: Cube stopped / unreachable → 503 for JSON, CSV and PDF; a stale-version situation →
  503 (simulate as needed, e.g. a stub or by pausing the refresh);
- saved-report run, CSV and PDF totals equal the JSON totals with the `cube` engine.

Also keep `node --test` in `services/analytics` passing and extend it for any `security.js` change.

## 8. Ownership

| Owner | Files |
|---|---|
| **Integrator** | `apps/api/pom.xml`, `db/migration/**` (V11 done), `infra/**`, this contract, `README.md`, `docs/analytics.md`, `docs/reports.md`, `apps/web/**`, merges |
| **backend** agent | `apps/api/**` except the above, `services/analytics/**` (model, `cube.js`, `security.js`, tests, `scripts/reconcile.mjs`), `docs/cube-reports.md` |

If `pom.xml`, a migration or `infra/**` must change, the agent stops and reports what is needed.
