# Analytics (Cube semantic layer)

A [Cube](https://cube.dev) data model over the Insight Studio PostgreSQL schema. It defines the
same metrics as the Spring API (revenue, orders, units, average order value) so other tools can
query them, and a reconciliation script proves the two agree.

```
cube.js                  Cube config (pre-aggregation refresh timezones)
model/cubes/orders.yml   orders: one row per receipt with >= 1 line item; daily rollup by store
model/cubes/line_items.yml  line_items: sale items for product/category breakdowns; daily rollup by store and category
model/cubes/catalog.yml  businesses, stores, products
scripts/reconcile.mjs    compares Cube with the API (Node 20+, no dependencies)
```

## Run

From the repository root, with `infra/.env` containing `CUBEJS_API_SECRET` (see `infra/.env.example`):

```sh
docker compose -f infra/compose.yaml --profile analytics up -d
```

Cube listens on <http://localhost:4000> (Playground in dev mode). Queries must pass the business's
time zone (`"timezone": "America/New_York"` for the demo data) so day/week/month buckets match the API.

## Reconcile with the API

With the API running (demo data loaded) and Cube up:

```sh
CUBE_URL=http://localhost:4000 API_URL=http://localhost:8080 CUBEJS_API_SECRET=<same secret> \
  node services/analytics/scripts/reconcile.mjs
```

It exits non-zero if any figure differs by more than 0.005. See [docs/analytics.md](../../docs/analytics.md)
for the model, measures and limitations.
