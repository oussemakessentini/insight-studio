# Saved reports and PDF export: contract

Binding agreement between the integrator, the **backend** agent and the **frontend** agent for this
phase. Change it only through the integrator. Existing behaviour (report JSON, CSV export,
accounts, roles, isolation) must keep working unchanged.

## 1. What a saved report is

A named definition of one of the two existing reports, belonging to one business:

| Field | Meaning |
|---|---|
| `name` | 1–120 characters, trimmed, no control characters; unique per business ignoring case (`409` otherwise) |
| `kind` | `monthly` or `categories` (the same report the Reports page shows) |
| `range` | `{ "type": "fixed", "from": "2026-06-01", "to": "2026-08-31" }` or `{ "type": "relative", "preset": "last_30_days" }` |
| `storeId` | a store of the same business, or `null` for all stores |

Running a saved report resolves its range **at run time** to a concrete `period {from, to}` and then
calls the existing `ReportService.monthly` / `ReportService.categories`, so a saved report, the
Reports page, the CSV and the PDF always show the same figures.

## 2. Date ranges

- Dates are calendar dates in the **business's time zone** (`businesses.time_zone`), exactly like
  the report API. "Today" means the current date in that zone, not the server's.
- `fixed`: `from <= to`, at most `ReportingContext.MAX_RANGE_DAYS` days (same rule as the API).
- `relative` presets, resolved against *today* in the business time zone:

| `preset` | Period |
|---|---|
| `last_7_days`, `last_30_days`, `last_90_days`, `last_365_days` | the N days ending today, inclusive |
| `month_to_date` | 1st of this month → today |
| `previous_month` | the whole previous calendar month |
| `last_3_months`, `last_12_months` | the 3 / 12 whole calendar months before this month |
| `quarter_to_date` | 1st day of this quarter → today |
| `previous_quarter` | the whole previous calendar quarter |
| `year_to_date` | 1 January → today |
| `previous_year` | the whole previous calendar year |

  Note: the dashboard's own "Last 30 days" filter ends on the last day **with sales**; saved
  relative ranges end **today**, because they are meant to be re-run over time. Every response
  includes the resolved `period`, and the UI always shows it.
- Resolution must be a pure function `(preset, today) -> DateRange` (unit-testable with any date),
  with `today = LocalDate.now(clock.withZone(businessZone))` and an injectable `java.time.Clock`.

## 3. API (all JSON errors are RFC 9457 problem details)

All saved-report endpoints need a signed-in member of the business resolved by
`CurrentBusiness` (`X-Business-Id` selector, as everywhere). The public demo has no saved reports:
anonymous calls are `401`.

| Method & path | Who | Answer |
|---|---|---|
| `GET /api/saved-reports` | VIEWER+ | `200 [SavedReport]`, ordered by name (case-insensitive) |
| `POST /api/saved-reports` `{name, kind, range, storeId}` | ADMIN+ (verified email, via `require(Role.ADMIN)`) | `201 SavedReport` |
| `GET /api/saved-reports/{id}` | VIEWER+ | `200 SavedReport` |
| `PUT /api/saved-reports/{id}` `{name, kind, range, storeId}` | ADMIN+ | `200 SavedReport` (rename = PUT with a new name; also edits filters) |
| `DELETE /api/saved-reports/{id}` | ADMIN+ | `204` |
| `GET /api/saved-reports/{id}/report` | VIEWER+ | `200 {savedReport: SavedReport, monthly: MonthlyReportResponse}` or `{savedReport, categories: CategoryReportResponse}` (exactly one of the two keys) |
| `GET /api/saved-reports/{id}/report.csv` | VIEWER+ | the existing CSV for the resolved period |
| `GET /api/saved-reports/{id}/report.pdf` | VIEWER+ | PDF (§4), titled with the saved report's name |
| `GET /api/reports/monthly.pdf`, `GET /api/reports/categories.pdf` (`from`, `to`, `storeId` as for `.csv`) | as the JSON report (VIEWER+, and the public demo when enabled) | PDF of the ad-hoc report |

`SavedReport`:

```json
{
  "id": 7,
  "name": "Q3 by month",
  "kind": "monthly",
  "range": { "type": "relative", "preset": "previous_quarter", "from": null, "to": null },
  "storeId": 3,
  "storeName": "Boston",
  "period": { "from": "2026-07-01", "to": "2026-09-30" },
  "createdBy": "Olive Owner",
  "createdAt": "2026-10-01T09:00:00Z",
  "updatedAt": "2026-10-01T09:00:00Z"
}
```

- `range.from`/`range.to` are set for `fixed`, `range.preset` for `relative` (the others `null`).
- `period` is the range resolved *now* (§2).
- Validation `400`: unknown `kind`, `range.type` or `preset`; missing dates; `from > to`; too long a
  range; blank or too long `name`. A `storeId` that is not a store **of this business** is `404
  "Store N was not found."` (never confirms other businesses' ids).
- **Isolation**: a definition is always loaded by `(id, business_id)` with the business from
  `CurrentBusiness`; another business's id is `404 "Saved report not found."` for every endpoint,
  including exports, whatever header is sent. The database also enforces that `store_id` belongs to
  the definition's business (composite foreign key, §5).
- Writes need `require(Role.ADMIN)` (so VIEWERs get `403`, unverified accounts `403`, the demo `401`).
- Exports set `Content-Disposition: attachment; filename="<business-slug>-<kind>-<from>-to-<to>.<ext>"`;
  filenames contain only the slug, the kind and ISO dates (never user text such as the report name).

## 4. PDF

Generated on the server with OpenPDF (`com.github.librepdf:openpdf`) from the **same**
`MonthlyReportResponse` / `CategoryReportResponse` the JSON endpoint returns (no separate queries or
calculations). A4 portrait, embedded font (an OFL-licensed sans such as Noto Sans or Inter, shipped
in `src/main/resources/fonts/` with its licence) so any business name or currency symbol renders.

Contents, in order:

1. Header: "Insight Studio", the report title (saved report name, or "Monthly report" /
   "Category report"), the business name.
2. Details block: period (`1 Jul 2026 – 30 Sep 2026`, day count), time zone, store ("All stores" or
   name + code), range description for saved reports ("Previous quarter (rolling: from today's date in
   America/New_York)" / "Fixed dates"),
   currency.
3. Summary metrics: revenue, orders, units, average order value (categories: also average unit price).
4. The table, identical columns and values to the CSV: monthly (month, revenue, orders, units,
   AOV, change vs previous month, coverage with partial months marked) or categories (category,
   revenue, share, units, orders, average unit price), followed by a **totals row equal to the JSON
   totals**.
5. Notes: revenue at the prices charged; an order is a receipt with at least one item; partial months;
   category orders can overlap.
6. Footer on every page: "Generated <date time> (<business time zone>)" and "Page X of Y".

Long tables break across pages with the header row repeated and no row split across pages. An empty
result (no sales or no categories) still produces a valid PDF with the details, zero metrics and an
explicit "No sales in this period" message. Money is formatted with the business currency.

## 5. Schema (integrator-owned: `V10__create_saved_reports.sql`)

```sql
saved_reports (id, business_id, name, kind, range_type, date_from, date_to, relative_preset,
               store_id, created_by, created_at, updated_at)
```

with checks for the allowed values, `fixed` ⇔ both dates and no preset, `relative` ⇔ a preset and
no dates, `date_from <= date_to`, unique `(business_id, lower(name))`, and
`FOREIGN KEY (store_id, business_id) REFERENCES stores (id, business_id)`.

## 6. Ownership (one writer per file)

| Owner | Files |
|---|---|
| **Integrator** | `apps/api/pom.xml`, `db/migration/V10__*`, this contract, `README.md`, `docs/reports.md`, merges |
| **backend** agent | everything else under `apps/api/**`: a new `savedreport` package, PDF code (`report/pdf/**` or similar), `ReportController`/`ReportService` additions, `SecurityConfig` only if needed, fonts under `src/main/resources/fonts/`, all backend tests; `docs/saved-reports-api.md` |
| **frontend** agent | `apps/web/**` only; `docs/frontend-saved-reports.md` |

Neither agent pushes, merges into `main`, or edits the other's files.
