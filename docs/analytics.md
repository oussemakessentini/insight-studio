# Analytics: Cube semantic layer

`services/analytics` holds a [Cube](https://cube.dev) data model over the Insight Studio
PostgreSQL schema. It exposes the same metrics as the Spring API (revenue, orders, units,
average order value, category revenue) through Cube's REST / GraphQL / SQL APIs, so BI tools
and notebooks can query them without re-implementing the business rules.
`services/analytics/scripts/reconcile.mjs` checks that Cube's numbers equal the API's.

This is a first iteration: the API and the web app do not use Cube yet.

## Run it locally

1. Add a Cube secret to `infra/.env` (see `infra/.env.example`):

   ```sh
   node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
   # CUBEJS_API_SECRET=<that value>
   ```

2. Start the database and Cube. Cube is behind the `analytics` Compose profile, so the usual
   `docker compose up -d` still starts only PostgreSQL.

   ```sh
   docker compose -f infra/compose.yaml --profile analytics up -d
   ```

3. Load data (e.g. run the API once with the `demo` profile), then open the Playground at
   <http://localhost:4000>.

The image is pinned to `cubejs/cube:v1.7.46`. Cube reads the same `POSTGRES_*` values as the
database container, mounts `services/analytics` as its config directory, and listens on
`127.0.0.1:4000` only.

### Dev mode

The Compose service runs with `CUBEJS_DEV_MODE=true`: Playground, hot reload of the model,
an embedded Cube Store for pre-aggregations, and on-demand pre-aggregation builds.
**In dev mode Cube does not enforce authentication** (its log says "Authentication checks are
disabled in developer mode"), so anyone who can reach port 4000 can query all data. That is
acceptable only because the port is bound to localhost. A deployment needs dev mode off
(`CUBEJS_DEV_MODE=false`, `NODE_ENV=production`), a separate Cube Store and refresh worker,
and a strong `CUBEJS_API_SECRET`.

The embedded Cube Store keeps its files inside the container (`CUBESTORE_DATA_DIR` /
`CUBESTORE_REMOTE_DIR` under `/cube/cubestore`). Its default, `services/analytics/.cubestore/`
on the bind mount, made pre-aggregation builds fail with "No such file or directory" on Docker
Desktop for Windows. Rollups are therefore rebuilt when the container is recreated, which takes
seconds at this volume. (`.cubestore/` stays git-ignored in case Cube is run without these
variables.)

## Data model

| Cube | Grain | Source |
| --- | --- | --- |
| `orders` | one row per **order**: a receipt with at least one line item | `sales` inner-joined to `sale_items`, grouped by receipt |
| `line_items` | one row per sale item | `sale_items` joined to `sales` (for store and time) |
| `stores`, `products`, `businesses` | reference data | tables of the same name |

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
  "filters": [{ "member": "businesses.slug", "operator": "equals", "values": ["fieldstone-apparel"] }],
  "timezone": "America/New_York"
}
```

`dateRange` dates are inclusive calendar dates in that time zone, matching the API's
`from`/`to` parameters.

### Pre-aggregations

| Rollup | Measures | Dimensions | Time |
| --- | --- | --- | --- |
| `orders.daily_by_store` | count, revenue, units | `orders.store_id`, `businesses.slug` | day |
| `line_items.daily_by_store_category` | revenue, units, count | `line_items.store_id`, `products.category`, `businesses.slug` | day |

All rolled-up measures are additive, so these two rollups serve totals, day/week/month series,
per-store and per-category queries, and AOV (derived from revenue and count). Rollups are
built per query time zone; `CUBEJS_SCHEDULED_REFRESH_TIMEZONES` (default `America/New_York`)
tells the refresh scheduler which zones to build ahead of time. They refresh every hour, so
Cube can lag new sales by up to an hour.

The rollups are **not partitioned**, on purpose. With `partition_granularity: month`,
Cube v1.7.46 produced wrong numbers that the reconciliation caught:

- a partition built on demand is clipped to the requesting query's date range, and that
  partial table is then reused: a query for 2026-03-01 built a "March" partition holding only
  March 1, so a later March query returned 60 orders instead of 1,622;
- with the default build range (MIN/MAX of the time dimension), the last partition ended at
  the latest sale's local wall-clock time read as UTC (`2026-08-31 23:51:53+00` instead of
  `2026-09-01 03:51:53+00`), dropping that evening's sales.

A single daily table is a few thousand rows here and is rebuilt whole on each refresh.
Revisit partitioning (with explicit build ranges and a refresh worker that builds partitions
before queries arrive) once data volume requires it.

## Reconciliation

With the API and Cube running against the same database:

```sh
CUBE_URL=http://localhost:4000 API_URL=http://localhost:8080 CUBEJS_API_SECRET=<secret> \
  node services/analytics/scripts/reconcile.mjs
```

Node 20+, no dependencies; the Cube token is signed locally with HS256 from the environment
variable (no secrets in files). The script reads the business slug, time zone, stores and data
range from `/api/dashboard/context`, then for all stores and each store compares:

- revenue, orders, units and AOV from `/api/dashboard/summary` over the last 7 and 30 days of
  data, 2026-06-01..2026-08-31, the first and last day, and the whole data range;
- monthly revenue/orders (whole range) and weekly revenue/orders (June-August, including
  partial weeks) from `/api/dashboard/revenue`;
- revenue and units per category against `/api/products` rows summed by category, for every
  window above.

It prints how many Cube queries were served from pre-aggregations and exits `1` on any
difference above 0.005 (`2` on errors). Against the demo data (plus one receipt without items
added to check the exclusion rule) it ran 740 checks with 0 mismatches, all 70 Cube queries
served from the two rollups.

## Limitations and follow-ups

- **Not wired into the app.** Serving dashboard queries from Cube is later work.
- **Dev mode only** (see above): no authentication, embedded Cube Store, on-demand builds.
- **Security:** `CUBEJS_API_SECRET` signs every token; anyone holding it can query all
  businesses. There is no security context or row-level scoping yet.
- **Multi-business scoping:** measures cover every business; callers must filter on
  `businesses.slug` (or `stores.business_id` / `products.business_id`) and pass that
  business's time zone. A `queryRewrite` enforcing the business from the token is the natural
  next step. Pre-aggregations are built only for the time zones listed in
  `CUBEJS_SCHEDULED_REFRESH_TIMEZONES` ahead of time; others are built on first query.
- **Freshness:** rollups refresh hourly.
- The CSV import work on another branch (`import_batches`, `sales.import_batch_id`) is not
  referenced by the model; imported sales count like any other sales.
