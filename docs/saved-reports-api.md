# Saved reports and PDF export (API)

What the backend implements for [saved-reports-contract.md](saved-reports-contract.md). The contract
is binding; this page describes the code and the details the contract leaves open.

## Endpoints

| Method & path | Who | Answer |
|---|---|---|
| `GET /api/saved-reports` | member (VIEWER+) | `200 [SavedReport]`, by `lower(name)`, then id |
| `POST /api/saved-reports` | `require(Role.ADMIN)` | `201 SavedReport` |
| `GET /api/saved-reports/{id}` | member | `200 SavedReport` |
| `PUT /api/saved-reports/{id}` | `require(Role.ADMIN)` | `200 SavedReport` (full replacement; rename = new `name`) |
| `DELETE /api/saved-reports/{id}` | `require(Role.ADMIN)` | `204` |
| `GET /api/saved-reports/{id}/report` | member | `200 {savedReport, monthly}` or `{savedReport, categories}` |
| `GET /api/saved-reports/{id}/report.csv` | member | the existing CSV for the resolved period |
| `GET /api/saved-reports/{id}/report.pdf` | member | PDF titled with the definition's name |
| `GET /api/reports/monthly.pdf`, `/api/reports/categories.pdf` | as the JSON report (also the public demo) | PDF of the ad-hoc report; `from`, `to`, `storeId` as for `.csv` |

Request body of `POST`/`PUT`:

```json
{ "name": "Q3 by month", "kind": "monthly",
  "range": { "type": "fixed", "from": "2026-07-01", "to": "2026-09-30" }, "storeId": 3 }
{ "name": "Last quarter", "kind": "categories",
  "range": { "type": "relative", "preset": "previous_quarter" }, "storeId": null }
```

- `name` is trimmed; `kind`, `range.type` and `preset` are exact lowercase codes. For `fixed`, a
  `preset` is ignored; for `relative`, `from`/`to` are ignored (and stored as `NULL`).
- The run response omits the key of the other kind (`@JsonInclude(NON_NULL)`); `monthly` /
  `categories` are exactly the `/api/reports/*` JSON for the resolved period and store.
- `createdBy` is the creator's display name; `createdAt`/`updatedAt` are ISO instants (UTC).

### Errors (RFC 9457 problem details)

| Status | `detail` |
|---|---|
| 400 | `Enter a name for the saved report.` · `The name may be at most 120 characters.` · `The name contains invalid characters.` (control characters) · `'kind' must be monthly or categories.` · `'range' is required.` · `'range.type' must be fixed or relative.` · `'range.preset' must be one of last_7_days, …` · `'range.from' is required for a fixed range.` · `'range.from' must be a date such as 2026-07-01.` · `'from' (…) must be on or before 'to' (…).` · `The date range may cover at most 1098 days.`; a malformed or missing JSON body is a plain 400 |
| 401 | not signed in (also when the public demo is on: `/api/saved-reports/**` is not demo-readable) |
| 403 | VIEWER: `You need the ADMIN role for this.`; unverified email: the usual "Verify your email address first…"; missing/invalid CSRF header |
| 404 | `Saved report not found.` (unknown id or another business's id, for every endpoint); `Store N was not found.` (unknown store or another business's store); `Business not found.` (an `X-Business-Id` the caller is not a member of) |
| 409 | `A saved report named '<name>' already exists.` (same name ignoring case in the business; checked first, and the unique index decides races) |

Order of checks for writes: membership and role (`require(Role.ADMIN)`), then for `PUT`/`DELETE`
the definition's existence in the current business, then the body.

## Date ranges

- `RelativePreset.resolve(today)` is a pure function (unit-tested for every preset, month, quarter
  and year boundaries and leap years).
- `PeriodResolver` computes `today = LocalDate.now(clock.withZone(businessZone))` with the `Clock`
  bean from `common/ClockConfiguration` (system UTC clock; tests can supply a fixed one).
- `period` in every `SavedReport` is resolved at response time; fixed ranges are validated with the
  same rules as `ReportingContext.resolveFilter`.

## Files

- Filenames: `<business-slug>-<kind>-<from>-to-<to>.<csv|pdf>` for saved-report CSV/PDF and the
  ad-hoc PDFs (`ReportFiles.filename`). The **ad-hoc CSV keeps its original name**
  `monthly-<from>-to-<to>.csv` / `categories-…` so the existing export is unchanged.
- `Content-Type: application/pdf` / `text/csv;charset=UTF-8`, `Content-Disposition: attachment`.

## PDF (`report/pdf`)

- `ReportPdf` (OpenPDF 3, package `org.openpdf.text`) builds A4 portrait documents from the
  `MonthlyReportResponse` / `CategoryReportResponse` only, plus labels (`ReportPdfDetails`: title,
  business name, currency, time zone, store label, range description). No queries or figure
  calculations; `PdfFormats` only formats values.
- Layout: "Insight Studio", title, business name; details (period with day count, time zone,
  store, range description for saved reports, currency); summary tiles (revenue, orders, units,
  average order value, and average unit price for categories; the value font shrinks to fit);
  the table with the CSV's columns and a totals row equal to the JSON totals (monthly: blank
  change/coverage cells; categories: blank share cell); notes; footer on every page with
  `Generated <d MMM yyyy, HH:mm> (<business time zone>)` and `Page X of Y`.
- Tables repeat their header row on every page and never split a row. Without orders
  (`totals.orders == 0`) the table is replaced by "No sales in this period".
- Two passes: the first counts the pages, the second prints `Page X of Y`.
- Money: `NumberFormat` with the business currency, US-style grouping, always two decimals like the
  JSON (`€1,234.50`, `$0.00`, `CHF 1,234.50`).
- Font: Noto Sans Regular/Bold (static TTFs from notofonts) embedded as Identity-H, licence in
  `src/main/resources/fonts/OFL.txt`. It covers Latin, Greek and Cyrillic; CJK text would not
  render.

## Tests

- `savedreport/RelativePresetTest`, `PeriodResolverTest` (time zones around midnight with a fixed clock).
- `report/pdf/ReportPdfTest`: layout from hand-built responses; writes samples to
  `apps/api/target/pdf-samples/` (`pdftoppm -r 80 -png file.pdf prefix` to look at them).
- `savedreport/SavedReportApiIntegrationTest`: CRUD and validation, the permission matrix, CSRF,
  cross-business isolation, and JSON = CSV = PDF totals and saved run = `/api/reports/*`.
- `savedreport/SavedReportPublicDemoIntegrationTest`: ad-hoc PDFs for the public demo, saved
  reports 401.
