# Analytics (Cube semantic layer)

A [Cube](https://cube.dev) data model over the Insight Studio PostgreSQL schema. It defines the
same metrics as the Spring API (revenue, orders, units, average order value), and a
reconciliation script proves the two agree. Cube is private: only the Spring API calls it, with a
short-lived token that names one business, and every query is scoped to that business.

```
cube.js                     Cube config: security hooks, refresh contexts and time zones
security.js                 checkAuth (HS256 JWT with businessId), queryRewrite (mandatory business filter), app ids
model/cubes/orders.yml      orders: one row per receipt with >= 1 line item; daily rollup by business and store
model/cubes/line_items.yml  line_items: sale items for product/category breakdowns; daily rollup by business, store and category
model/cubes/catalog.yml     businesses, stores, products
scripts/reconcile.mjs       compares Cube with the API (Node 20+, no dependencies)
test/security.test.js       unit tests for security.js: node --test services/analytics/test/security.test.js
```

## Run

From the repository root, with `infra/.env` containing `CUBEJS_API_SECRET` (at least 32
characters; see `infra/.env.example`):

```sh
docker compose -f infra/compose.yaml --profile analytics up -d
```

Cube runs in production mode (no Playground) on `127.0.0.1:4000`, with a separate Cube Store.
Every request needs `Authorization: <HS256 JWT>` with a `businessId` claim and an `exp` at most
an hour ahead. Set `INSIGHT_CUBE_URL=http://localhost:4000` for the API to serve
`/api/analytics/summary`. Queries must pass the business's time zone
(`"timezone": "America/New_York"` for the demo data) so day/week/month buckets match the API.
Restart Cube after changing the model.

## Reconcile with the API

With the API running (demo data loaded) and Cube up:

```sh
CUBE_URL=http://localhost:4000 API_URL=http://localhost:8080 CUBEJS_API_SECRET=<same secret> \
  node services/analytics/scripts/reconcile.mjs
```

The business id comes from `/api/session` (public demo) or `BUSINESS_ID`. It exits non-zero if
any figure differs by more than 0.005. See [docs/analytics.md](../../docs/analytics.md) for the
security model, model, measures and limitations.
