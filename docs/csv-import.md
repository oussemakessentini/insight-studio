# CSV import of historical sales

Insight Studio can load past sales from a CSV file: validate it first (dry run), then import it in
one all-or-nothing step. Every successful import is kept in an import history.

> **Local development only.** The import endpoints write data and have no authentication. They are
> **disabled by default** and exist only when `insight.imports.enabled=true`, which is set only by
> the `local` Spring profile (`application-local.properties`). Without it, `/api/imports` answers
> 404 like any unknown path, no import bean is created, and the web app hides the Import pages.
> Never activate the `local` profile in a deployed environment.

## Enabling imports locally

Run the API with the `local` profile in addition to `demo` (from `apps/api`):

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo,local"
```

Then open **Import** in the web app (`/imports`). Imports write to whatever database the API is
connected to, so point it at a database you are happy to change.

## File format

- UTF-8 text (a byte-order mark is fine, as saved by Excel's "CSV UTF-8"), comma-separated,
  quoted per [RFC 4180](https://www.rfc-editor.org/rfc/rfc4180): a value may be wrapped in double
  quotes, and then may contain commas, line breaks and `""` for a literal quote. CRLF or LF line
  endings. Completely empty lines are ignored.
- The file name must end in `.csv`; at most 5 MB and 50,000 data rows per file.
- The first line is the header, exactly these columns in this order (case and surrounding spaces
  don't matter):

  ```
  store_code,receipt_number,sold_at,sku,quantity,unit_price
  ```

- One row per **line item**. Rows with the same `store_code` and `receipt_number` form one receipt
  (a sale); they don't need to be next to each other.

| Column | Rule |
| --- | --- |
| `store_code` | Code of an existing store of the current business, e.g. `BOS` (case-sensitive) |
| `receipt_number` | 1–40 characters |
| `sold_at` | ISO-8601 date-time. With an offset (`2026-09-01T14:30:00-04:00`, `2026-09-01T18:30:00Z`) it is used as is; without one (`2026-09-01T14:30:00`) it is a local time in the business's time zone. A local time that doesn't exist because of a daylight-saving change is an error; an ambiguous one (clocks going back) takes the earlier offset |
| `sku` | SKU of an existing product of the current business, e.g. `TOP-001` (case-sensitive) |
| `quantity` | Whole number greater than 0 |
| `unit_price` | Price actually charged per unit: a number of at least 0 with at most 2 decimals, e.g. `24.50` (no currency symbol, thousands separator or sign) |

Values are trimmed of surrounding spaces.

## Rules checked before anything is written

- All rows of a receipt must have the same `sold_at` (compared as instants, so `14:30-04:00` and
  `18:30Z` agree).
- A SKU may appear only once per receipt; combine the quantities into one row.
- A receipt that already exists for that store is an error on each of its rows. Existing receipts
  are never overwritten or extended.
- A file whose content (SHA-256 of the bytes) was already imported into the business is rejected.
  The same file can still be imported into a different business.
- Stores, products, existing receipts and previous files are all looked up **in the current
  business only**.

Errors point at the **physical line** in the file (the header is line 1; a quoted value spanning
several lines moves later line numbers accordingly) and at the column name. A result lists the first
100 errors and `errorCount` gives the total.

A file that can't be read at all is refused with HTTP 400 before any row is checked: an empty file,
a file that isn't UTF-8 text, a name without `.csv`, broken quoting, a missing or different header,
or a header with no data rows. Files over 5 MB get HTTP 413.

## All or nothing

- **Dry run** (the default, `dryRun=true`) validates and reports counts and the total but never
  writes; it runs in a read-only transaction.
- **Import** (`dryRun=false`) validates again and, only if there are no errors at all, writes the
  import batch, its receipts (`sales.import_batch_id` points to the batch) and their line items in
  a single transaction. Imports into the same business are serialised, so two uploads can't both
  pass the duplicate checks.
- Any error means status `REJECTED` and nothing is written. Only successful imports are stored in
  the history.

Imported receipts are ordinary sales: they appear on the dashboard, in Sales, Products and Stores,
and count as orders like any other receipt with line items.

## API

`POST /api/imports?dryRun=true|false` (multipart form, field `file`) returns HTTP 200 with:

```json
{
  "batchId": null,
  "status": "VALIDATED",
  "dryRun": true,
  "fileName": "sample-import.csv",
  "rowCount": 12,
  "saleCount": 8,
  "lineCount": 12,
  "totalAmount": 860.10,
  "errors": [],
  "errorCount": 0
}
```

`status` is `VALIDATED` (dry run without errors), `IMPORTED` (written; `batchId` is set) or
`REJECTED` (see `errors`, each `{ "line": 4, "column": "sku", "message": "Unknown SKU 'X'." }`;
`line` and `column` are `null` for problems with the whole file, such as a repeated import). For
rejected files the counts cover the rows that could be read.

`GET /api/imports?page=0&size=20` lists the business's imports, newest first
(`page` ≥ 0, `size` 1–100): `{ page, size, totalItems, totalPages, items: [{ batchId, fileName,
rowCount, saleCount, lineCount, totalAmount, createdAt }] }`.

`GET /api/imports/{id}` returns one import with the same fields plus `firstSoldAt` and `lastSoldAt`
of its receipts, or 404 "Import N was not found." (also for another business's import).

## Sample file

[`sample-import.csv`](sample-import.csv) matches the demo business (stores BOS, CAM, PVD and WEB,
SKUs from the demo catalogue) and is dated after the demo data ends (2026-08-31), so it imports
cleanly into a freshly seeded demo database: 12 rows, 8 receipts, 12 line items, 15 units,
total $860.10. Importing it a second time is rejected as a duplicate file.
