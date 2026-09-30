# Reports

On-demand **Monthly** and **Category** reports with CSV export. Reports are computed on each
request from the sales tables; nothing is stored (saved or scheduled reports are later work).

## Endpoints

All are read-only `GET`s and take the same parameters as the dashboard:

| Parameter | Meaning |
|-----------|---------|
| `from`, `to` | Inclusive ISO dates (`yyyy-MM-dd`) in the business's time zone. Missing `to` = last day with sales; missing `from` = 30 days ending at `to`. At most 1098 days. |
| `storeId` | Optional store of the current business; another business's store (or an unknown id) is a 404. |

Errors are RFC 9457 problem details (`application/problem+json`), also for the CSV endpoints:
`from` after `to`, a malformed date, a non-positive `storeId` or a too-long range is a 400.

### `GET /api/reports/monthly`

One row per calendar month overlapping the period, zero-filled, with months bucketed in the
business's time zone (a sale at `2026-05-31T22:30Z` belongs to June for a business in Europe/Paris).

```json
{
  "period": { "from": "2026-03-20", "to": "2026-06-10" },
  "storeId": null,
  "rows": [
    { "month": "2026-03-01", "revenue": 0.00, "orders": 0, "unitsSold": 0, "averageOrderValue": 0.00,
      "revenueChangePercent": null, "daysCovered": 12, "daysInMonth": 31, "complete": false },
    { "month": "2026-04-01", "revenue": 50.00, "orders": 1, "unitsSold": 1, "averageOrderValue": 50.00,
      "revenueChangePercent": null, "daysCovered": 30, "daysInMonth": 30, "complete": true }
  ],
  "totals": { "revenue": 50.00, "orders": 1, "unitsSold": 1, "averageOrderValue": 50.00 }
}
```

- `month` is the first day of the month, so the first row may start before the period.
- `complete` is false when only `daysCovered` of `daysInMonth` days are inside the period (the
  same flags as the dashboard revenue series). Such rows hold fewer days of sales; the UI marks
  them "Partial".
- `revenueChangePercent` compares with the previous row (1 decimal); `null` for the first row or
  when the previous month's revenue is 0. It is computed even when a partial month is involved
  (the UI explains this in a tooltip).

### `GET /api/reports/categories`

Every category of the business's catalogue, including categories without sales in the period
(zeros), sorted by revenue (highest first), then by name.

```json
{
  "period": { "from": "2026-03-20", "to": "2026-06-10" },
  "storeId": null,
  "rows": [
    { "category": "Tops", "revenue": 160.00, "unitsSold": 8, "orders": 3,
      "revenueSharePercent": 47.1, "averageUnitPrice": 20.00 }
  ],
  "totals": { "revenue": 340.00, "unitsSold": 11, "orders": 5, "averageOrderValue": 68.00, "averageUnitPrice": 30.91 }
}
```

- A category's `orders` counts the orders containing at least one of its products, so an order
  spanning several categories counts once in each; `totals.orders` counts distinct orders and is
  therefore not the sum of the rows.
- `averageUnitPrice` = revenue / units (0 without sales). `revenueSharePercent` has 1 decimal and
  is 0 when the period has no revenue.

### `GET /api/reports/monthly.csv` and `GET /api/reports/categories.csv`

Same parameters and rows as the JSON endpoints, as a downloadable file:

- `Content-Type: text/csv;charset=UTF-8` (no byte-order mark)
- `Content-Disposition: attachment; filename="monthly-<from>-to-<to>.csv"`
  (`categories-<from>-to-<to>.csv`), using the resolved period, so defaults are spelled out.

## CSV format

- A header row, then one line per row. **No totals row**, so the file can be summed or filtered
  directly in a spreadsheet or loaded into another tool.
- RFC 4180: comma separator, CRLF line endings; fields containing a comma, double quote, CR or LF
  are enclosed in double quotes, and embedded quotes are doubled.
- Plain numbers: no currency symbol or thousands separator, `.` as decimal separator, money with
  2 decimals, percentages with 1. An empty field means "no value" (e.g. `revenue_change_percent`
  of the first month).
- Dates are ISO `yyyy-MM-dd`; `complete` is `true`/`false`.
- **Formula injection:** a text cell from the data (category names) starting with `=`, `+`, `-`,
  `@`, tab or CR gets a leading `'` so spreadsheet apps show it as text instead of evaluating it.
  Numeric cells are never altered, so negative changes stay numbers.

Columns:

```
monthly:    month,revenue,orders,units_sold,average_order_value,revenue_change_percent,days_covered,days_in_month,complete
categories: category,revenue,units_sold,orders,revenue_share_percent,average_unit_price
```

## Definitions and consistency

- Revenue = `SUM(sale_items.quantity * sale_items.unit_price)`, the price charged at the time of sale.
- An order is a receipt with at least one line item (`ReportSql.SALES_FROM` inner join).
- For the same filters, monthly totals and category totals equal `/api/dashboard/summary`
  revenue, orders and units (covered by `ReportApiIntegrationTest.AgreementWithDashboard`).
  Category totals come from the same SQL statement as the rows (`GROUPING SETS`), so their
  orders are distinct across categories.
- Category rows are built from the catalogue (`products.business_id`); a line item pointing at
  another business's product would be missing from them. The schema does not prevent that, but
  no code path creates such data.
- Ties in the category order are broken by name using the database collation.

## Implementation

- API (`apps/api/.../report/`): `ReportController` -> `ReportService`
  (`@Transactional(readOnly = true)`) -> `ReportQueries` (one aggregate SQL statement per report);
  `CsvWriter` (RFC 4180 and formula protection, no dependency) and `ReportCsv` (column layout);
  DTOs in `report/dto`.
- Web: `pages/ReportsPage.tsx` with Monthly/Categories tabs (`?report=monthly|categories`, the
  default omitted from the URL, restored on refresh and back/forward), the shared filter bar with
  the store picker, a totals row in each table, "Partial" badges on incomplete months, and an
  **Export CSV** link (`<a download>`) to the `.csv` endpoint with the current filters.
  Components in `components/reports/`, API calls and types in `api/reports.ts`, styles in
  `styles/reports.css`.
