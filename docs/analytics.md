# Analytics: Cube semantic layer

`services/analytics` holds a [Cube](https://cube.dev) data model over the Insight Studio
PostgreSQL schema. It defines the same metrics as the Spring API (revenue, orders, units,
average order value, category revenue), and `services/analytics/scripts/reconcile.mjs` checks
that Cube's numbers equal the API's.

**Cube is private.** The browser never talks to it: the Spring API is its only client, and every
request Cube accepts is scoped to exactly one business. The API exposes Cube results through
`GET /api/analytics/summary`.

## Security model

```
browser --(session)--> Spring API --(Bearer JWT: businessId, 60 s)--> Cube --(SQL + business filter)--> PostgreSQL
```

1. **The API chooses the business.** `AnalyticsService` takes the business id from
   `tenancy.CurrentBusiness.require().businessId()` (the caller's membership, or the public demo;
   see `docs/accounts-contract.md` §3). Nothing in the request (query parameters, `X-Business-Id`,
   body) is copied into the token. Dates, store and time zone come from
   `ReportingContext.resolveFilter(from, to, storeId)`, so a store of another business is a `404`
   before Cube is called; if `ReportingContext` ever resolved a different business than
   `CurrentBusiness`, the API refuses (500) rather than query Cube.
2. **Short-lived token.** The API signs an HS256 JWT with `CUBEJS_API_SECRET`:
   `{"businessId": <id>, "iat": <now>, "exp": <now + 60>}` (`analytics/CubeTokens.java`, plain
   `javax.crypto`, no JWT library). A fresh token is signed for every call to Cube.
3. **Cube verifies it** (`services/analytics/security.js`, wired in `cube.js`). `checkAuth`
   requires the header, `alg` exactly `HS256`, a valid signature (constant-time compare), an
   integer `exp` in the future and at most one hour ahead (30 s clock skew allowed), and a
   positive integer `businessId`. Only `{ businessId }` is kept as the security context.
   Missing token → `401`; any invalid token → `403`. A missing, short (< 32 characters) or
   placeholder (`change-me…`) secret makes Cube refuse every token (fail closed).
4. **Cube scopes every query.** `queryRewrite` collects every cube the query references
   (measures, dimensions, time dimensions, segments, order and nested `and`/`or` filters) and
   ANDs `<cube>.business_id = <token businessId>` for each (`businesses.id` for `businesses`).
   Top-level filters are ANDed, so user filters can only narrow the result; a filter naming
   another business returns nothing. Queries on a cube without a scope member, member
   expressions, or malformed filters are rejected (`403`). A new cube must be added to
   `SCOPE_MEMBER` in `security.js` before it can be queried.
5. **Narrow surface.** `contextToApiScopes` grants only `data` and `meta`: `/v1/sql`, GraphQL and
   pre-aggregation jobs answer `403`. `checkSqlAuth` refuses the SQL (Postgres wire) API, which
   is not enabled anyway.
6. **No dev mode, no Playground.** Compose runs Cube with `CUBEJS_DEV_MODE=false` and
   `NODE_ENV=production`. A custom `checkAuth` also runs in dev mode (verified: with dev mode on,
   requests without a token still got `401` and forged/expired tokens `403`, even though Cube
   logs "Authentication checks are disabled in developer mode"), but dev mode also exposes
   `/playground/*` endpoints and error stack traces, so it stays off.
7. **Network.** Cube is published on `127.0.0.1:4000` only (the API runs on the host); Cube Store
   is not published at all. The secret lives only in `infra/.env` / the API's environment.

### Cache and pre-aggregations per business

- `contextToAppId` → `insight_business_<id>`: each business has its own app id (compiled model
  and query-cache identity). The background refresh has no business and uses `insight_refresh`.
- `contextToOrchestratorId` → `insight_shared`: rollups are **shared** by all businesses and
  carry the business as a dimension (`orders.business_id`, `line_items.business_id`,
  `products.business_id`), so one refresh builds them once and the mandatory filter selects the
  business's rows. Result-cache keys contain the generated SQL, which includes the business
  filter, so cached results are never shared between businesses.
- `scheduledRefreshContexts` returns one context (no business) because the rollups are shared.

## Configuration

| Where | Variable | Meaning |
| --- | --- | --- |
| API | `INSIGHT_CUBE_URL` (`insight.cube.url`) | Cube base URL, e.g. `http://localhost:4000`. Empty (default) → `/api/analytics/**` answers `503 "Analytics is not configured."` |
| API + Cube | `CUBEJS_API_SECRET` | Shared HS256 secret, at least 32 characters. The API also accepts `insight.cube.api-secret` (`INSIGHT_CUBE_API_SECRET`), which wins when set. |
| Cube | `CUBEJS_SCHEDULED_REFRESH_TIMEZONES` | Zones rollups are built for ahead of time (default `America/New_York`). |

The API reads these from real environment variables or from `infra/.env`, which it imports on
startup. A URL without a usable secret (missing, shorter than 32 characters, or the
`.env.example` placeholder) fails API startup with a message naming `CUBEJS_API_SECRET`.
`application.properties` has no `insight.cube.*` entries; the defaults live in
`CubeProperties` / `CubeConfiguration` (empty URL = off).

## API

`GET /api/analytics/summary?from&to&storeId` (same parameters and defaults as
`/api/dashboard/summary`; dates are inclusive, in the business's time zone):

```json
{ "period": { "from": "2026-01-01", "to": "2026-12-31" },
  "revenue": 1555513.20, "orders": 10397, "unitsSold": 21471, "averageOrderValue": 149.61,
  "source": "cube" }
```

The API posts one query to `POST /cubejs-api/v1/load` (`orders.revenue`, `orders.count`,
`orders.units` over `orders.sold_at` with the date range, an `orders.business_id` filter (Cube
adds its own anyway), an optional `orders.store_id` filter, and the business's `timezone`),
polls while Cube answers `Continue wait` (up to 30 s), and rounds like the dashboard (money
scale 2, AOV = rounded revenue / orders).

Errors: `503` when not configured; `502 "Analytics is temporarily unavailable."` for any Cube
failure (connection, timeout, non-2xx, `error` in the body, malformed data). Cube's message is
logged, never returned; a `401`/`403` from Cube is logged as a probable secret mismatch.
`400`/`404` come from the usual filter validation.

## Run it locally

1. Add a secret to `infra/.env` (see `infra/.env.example`) and point the API at Cube:

   ```sh
   node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
   # CUBEJS_API_SECRET=<that value>
   # INSIGHT_CUBE_URL=http://localhost:4000
   ```

2. Start the database, Cube and Cube Store. They are behind the `analytics` Compose profile, so
   the usual `docker compose up -d` still starts only PostgreSQL.

   ```sh
   docker compose -f infra/compose.yaml --profile analytics up -d
   ```

3. Start the API (e.g. with the `demo` profile) and call `GET /api/analytics/summary`.

The images are pinned to `cubejs/cube:v1.7.46` and `cubejs/cubestore:v1.7.46`. Cube reads the
same `POSTGRES_*` values as the database container, mounts `services/analytics` read-only as its
config directory, and runs the refresh worker in the same instance
(`CUBEJS_REFRESH_WORKER=true`). Cube Store keeps rollups in the named volume `cubestore_data`
(a Windows bind mount made rollup uploads fail). Model changes need a Cube restart
(`docker compose ... restart cube`): production mode does not hot-reload. The first start
compiles the model and builds the rollups, which can take a minute.

To query Cube by hand you need a token for one business, e.g. (Node):

```js
const { createHmac } = require('node:crypto');
const enc = (v) => Buffer.from(JSON.stringify(v)).toString('base64url');
const now = Math.floor(Date.now() / 1000);
const body = `${enc({ alg: 'HS256', typ: 'JWT' })}.${enc({ businessId: 1, exp: now + 60 })}`;
console.log(`${body}.${createHmac('sha256', process.env.CUBEJS_API_SECRET).update(body).digest('base64url')}`);
```

## Data model

| Cube | Grain | Source | Scoped by |
| --- | --- | --- | --- |
| `orders` | one row per **order**: a receipt with at least one line item | `sales` inner-joined to `sale_items`, grouped by receipt, with the store's `business_id` | `orders.business_id` |
| `line_items` | one row per sale item | `sale_items` joined to `sales` and `stores` | `line_items.business_id` |
| `stores`, `products` | reference data | tables of the same name | `<cube>.business_id` |
| `businesses` | reference data | `businesses` | `businesses.id` |

Joins: `orders -> stores -> businesses`, `line_items -> products -> businesses`,
`line_items -> stores`.

### Measures (identical to the API)

| Measure | Definition |
| --- | --- |
| `orders.revenue`, `line_items.revenue` | `SUM(sale_items.quantity * sale_items.unit_price)`, the price actually charged, never `products.list_price` |
| `orders.count` | receipts with at least one line item; receipts without items are excluded from every measure |
| `orders.units`, `line_items.units` | `SUM(sale_items.quantity)` |
| `orders.average_order_value` | `revenue / orders` |

`orders.count` is a plain `count` over a receipt-grain cube, not a `count_distinct`, so it is
additive and can be served from rollups. There is deliberately no order count on
`line_items` (distinct receipts per category are not additive).

### Time zones

Days, weeks (Monday start) and months are buckets in the business's time zone
(`businesses.time_zone`, `America/New_York` for the demo). **Every query must pass
`"timezone"`**, e.g.

```json
{
  "measures": ["orders.revenue", "orders.count", "orders.units"],
  "timeDimensions": [{ "dimension": "orders.sold_at", "granularity": "month",
                       "dateRange": ["2026-06-01", "2026-08-31"] }],
  "timezone": "America/New_York"
}
```

No business filter is needed in the query: Cube adds it from the token. `dateRange` dates are
inclusive calendar dates in that time zone, matching the API's `from`/`to` parameters.

### Pre-aggregations

| Rollup | Measures | Dimensions | Time |
| --- | --- | --- | --- |
| `orders.daily_by_store` | count, revenue, units | `orders.business_id`, `orders.store_id` | day |
| `line_items.daily_by_store_category` | revenue, units, count | `line_items.business_id`, `line_items.store_id`, `products.business_id`, `products.category` | day |

The business ids are rollup dimensions so the mandatory scope filters (which Cube adds to every
query) can still be served from the rollups; `products.business_id` is there because a query
touching `products.*` gets a `products.business_id` filter too. All rolled-up measures are
additive, so these two rollups serve totals, day/week/month series, per-store and per-category
queries, and AOV. Rollups are built per query time zone; `CUBEJS_SCHEDULED_REFRESH_TIMEZONES`
tells the refresh worker which zones to build ahead of time. They refresh every hour, so Cube
can lag new sales by up to an hour.

The rollups are **not partitioned**, on purpose. With `partition_granularity: month`,
Cube v1.7.46 produced wrong numbers that the reconciliation caught:

- a partition built on demand is clipped to the requesting query's date range, and that
  partial table is then reused: a query for 2026-03-01 built a "March" partition holding only
  March 1, so a later March query returned 60 orders instead of 1,622;
- with the default build range (MIN/MAX of the time dimension), the last partition ended at
  the latest sale's local wall-clock time read as UTC (`2026-08-31 23:51:53+00` instead of
  `2026-09-01 03:51:53+00`), dropping that evening's sales.

A single daily table is a few thousand rows here and is rebuilt whole on each refresh.
Revisit partitioning (with explicit build ranges) once data volume requires it.

## Reconciliation

With the API and Cube running against the same database:

```sh
CUBE_URL=http://localhost:4000 API_URL=http://localhost:8080 CUBEJS_API_SECRET=<secret> \
  node services/analytics/scripts/reconcile.mjs
```

Node 20+, no dependencies. The script reconciles the API's public demo business: its id comes
from `BUSINESS_ID` when set, otherwise from `GET /api/session` (`demo.businessId`, available once
the accounts work lands and the API runs with the `demo` profile). Each Cube request gets a
fresh 60-second token for that business, signed from the environment variable (no secrets in
files). It reads the time zone, stores and data range from `/api/dashboard/context`, then for
all stores and each store compares:

- revenue, orders, units and AOV from `/api/dashboard/summary` over the last 7 and 30 days of
  data, 2026-06-01..2026-08-31, the first and last day, and the whole data range;
- monthly revenue/orders (whole range) and weekly revenue/orders (June-August, including
  partial weeks) from `/api/dashboard/revenue`;
- revenue and units per category against `/api/products` rows summed by category, for every
  window above;
- when the API has Cube configured, `/api/analytics/summary` against `/api/dashboard/summary`
  (period, revenue, orders, units, AOV) for every window and store (skipped on `503`).

It prints how many Cube queries were served from pre-aggregations and exits `1` on any
difference above 0.005 (`2` on errors).

## Verification (2026-09-30)

Against a throwaway PostgreSQL seeded with the demo data (business 1, Fieldstone Apparel) plus a
second business inserted by SQL (business 2: 2 stores, 41 receipts of 3 units at 1,111.11),
with Cube in production mode, a separate Cube Store and the read-only config mount:

| Check | Result |
| --- | --- |
| SQL totals | business 1: 1,555,513.20 / 10,397 orders / 21,471 units; business 2: 136,666.53 / 41 / 123 |
| No token | `401 Authorization header is required` |
| Token signed with another secret; `alg: none` | `403 Invalid token` |
| Expired token; token without `businessId` | `403 Token expired`; `403 Token must name a business` |
| Token for business 1 / business 2 | exactly that business's SQL totals, served from `orders_daily_by_store` |
| Business 1 token + filter `orders.business_id = 2` | empty result |
| Business 1 token + `OR` filter trying to widen; grouped by `business_id`; store of business 2 | business 1 only; business 1 only; empty |
| Business 1 token listing `businesses`, `stores`, category revenue by `products.business_id` | business 1 rows only (`line_items_daily_by_store_category` used) |
| Unknown cube; `/v1/sql`; GraphQL | `403` |
| `GET /`; `/playground/context` | static "production mode" page; `404` |
| API `/api/analytics/summary` (also with `X-Business-Id: 2`) | business 1 totals, `source: "cube"` |
| `reconcile.mjs` (`BUSINESS_ID=1`) | 860 checks, 0 mismatches; 70/70 Cube queries served from the two rollups; 30 `/api/analytics/summary` comparisons |

Unit tests: `node --test services/analytics/test/security.test.js` (token verification and
query scoping) and the API's `analytics` tests (token signing/verification, stub-Cube
integration test, 503, 502 mapping).

## Limitations and follow-ups

- **Only the summary** is served through the API; the dashboard itself still uses SQL.
- **Single Cube instance** runs the API and the refresh worker; a deployment would split them
  and run Cube Store as a cluster.
- **Secret rotation** needs the API and Cube restarted with the new value.
- **Freshness:** rollups refresh hourly.
- **Product/store consistency:** `line_items` is scoped by the store's business and `products` by
  the product's business. A sale item whose product belongs to another business than the store
  is not prevented by the schema; such a row would be filtered out of product queries.
- The CSV import tables (`import_batches`, `sales.import_batch_id`) are not referenced by the
  model; imported sales count like any other sales.
