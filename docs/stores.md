# Stores

Per-store performance: a list of every store and a detail page per store, backed by read-only
endpoints under `/api/stores`.

## API

All endpoints are `GET`, read-only, and follow the dashboard conventions:

- `from` / `to` are inclusive ISO dates (`yyyy-MM-dd`) in the business time zone, resolved by
  `ReportingContext.resolveFilter` (same defaults, same 400s for an inverted or over-long range).
- Revenue is `SUM(sale_items.quantity * sale_items.unit_price)`, the prices actually charged.
- An order is a receipt with at least one line item; receipts without items are never counted.
- Errors are RFC 9457 problem details. An unknown store, or a store of another business, is a
  404 with `Store N was not found.`

| Endpoint | Response |
| --- | --- |
| `GET /api/stores?from&to` | `{ period, previousPeriod, stores: [{ storeId, code, name, city, revenue, orders, unitsSold, averageOrderValue, revenueSharePercent, revenueChangePercent }] }` |
| `GET /api/stores/{id}?from&to` | `{ store: { id, code, name, city }, period, previousPeriod, revenue, orders, unitsSold, averageOrderValue, categories: [{ category, revenue, unitsSold, orders, revenueSharePercent }] }` |
| `GET /api/stores/{id}/revenue?from&to&granularity` | Same shape as `/api/dashboard/revenue` (zero-filled buckets with `daysCovered`, `bucketDays`, `complete`) |
| `GET /api/stores/{id}/top-products?from&to&limit` | Same shape as `/api/dashboard/top-products`; `limit` 1–50, default 5 |

Details:

- **List.** Every store of the business is included, even stores without orders (zeros). Sorted
  by revenue, highest first, then name. `revenueSharePercent` is the share of all stores' revenue;
  `revenueChangePercent` compares with the previous period of equal length and is `null` when the
  store had no revenue then. The global `storeId` filter does not apply (the parameter is ignored).
  One SQL statement covers both periods: the previous window ends where the current one starts, so
  a single join over both windows with `FILTER` clauses computes the current totals and the
  previous revenue per store.
- **Detail.** `revenue`, `orders`, `unitsSold` and `averageOrderValue` are `MetricValue`s
  (`value`, `previousValue`, `changePercent`) against the previous period of equal length.
  `categories` is the store's revenue per product category, highest first; the shares add up to
  100% (subject to rounding). A category's `orders` counts receipts containing that category, so a
  receipt spanning several categories counts once in each and the category orders do not add up
  to the store's orders.
- **Revenue series and top products** are delegated to `DashboardService` with the path store id
  as the store filter, so they are identical to `/api/dashboard/revenue?storeId=` and
  `/api/dashboard/top-products?storeId=` by construction (no duplicated SQL).

### Consistency with the dashboard

For the same dates, the sum of `revenue` / `orders` / `unitsSold` across `/api/stores` equals
`/api/dashboard/summary`, and each store's detail metrics equal
`/api/dashboard/summary?storeId=`. `StoreApiIntegrationTest` asserts both on a hand-computed
fixture that includes a store without orders, receipts without items and another business's store.

## Web

- **`/stores`** (`StoresPage`): a table of every store with name (code · city), share of revenue
  (bar + percentage), revenue, change vs the previous period, orders, units and average order
  value, plus an "All stores" total row that matches the dashboard for the same dates. Rows open
  the store. On small screens the share moves under the store name and orders/units/average order
  columns are hidden, so the page never scrolls horizontally.
- **`/stores/:id`** (`StoreDetailPage`): metric cards compared with the previous period, the
  revenue trend (same chart as the dashboard, with granularity and chart/table toggles), the
  category mix, and the store's top products linking to `/products/:id`. An unknown id shows a
  "Store not found" state with a link back to the list.
- Both pages hide the store picker (`<FilterBar showStore={false} />`) because the store is either
  "all" or in the path; the date filters work as everywhere else, live in the URL and are carried
  by every link (`href(...)`), so deep links, refresh and Back/Forward restore the view.
- Code: `api/stores.ts` (types and calls), `components/stores/` (`StoreTable`, `CategoryMix`,
  `RevenueChange`), `styles/stores.css` (`stores-` classes; everything else reuses `index.css`).

## Decisions and limits

- A store without a city is shown as "Online", matching the dashboard's sales-by-store panel.
- The list's total row is summed in the browser from the rounded per-store figures; its average
  order value may differ from the dashboard's by a cent in rare rounding cases.
- "View all products" on the store page opens the product catalogue with the current global
  filters, not filtered to the store: the workspace owns the store filter and links cannot change
  it without a shared-code change.
