# CSV imports: stores, products and sales

Insight Studio can load a business's stores, its product catalogue and its past sales from CSV files.
Each file is validated first (dry run), then imported in one all-or-nothing step. The file's own
column names can be mapped to the import's fields, so an export from another system rarely needs
editing. Every real import attempt is kept in the import history.

The binding API agreement is [catalog-imports-contract.md](catalog-imports-contract.md); this page
explains the behaviour in detail.

## Who can import

Every import endpoint, including templates, previews and the history, needs the **ADMIN or OWNER**
role in the current business and a verified email address (`require(Role.ADMIN)`, see
[auth.md](auth.md)). Viewers get 403, signed-out visitors 401, the read-only public demo 403. Writes
need the CSRF header like every other write. Everything happens in the business resolved for the
request: stores, products, receipts, previous files and history are looked up there only.

## Kinds and fields

| Kind | Key (within the business) | Fields, in template order |
| --- | --- | --- |
| `stores` | `code` | `code`, `name`, `city` (optional) |
| `products` | `sku` | `sku`, `name`, `category`, `list_price` |
| `sales` | (`store_code`, `receipt_number`) | `store_code`, `receipt_number`, `sold_at`, `sku`, `quantity`, `unit_price` |

The usual order for a new business is stores, then products, then sales: a sales row must name an
existing store code and SKU of the business.

### Stores and products

The rules are exactly those of `POST /api/stores` and `POST /api/products` (shared code, same
messages):

| Field | Rule |
| --- | --- |
| `code`, `sku` | 1 to 50 letters, digits, `.`, `_` or `-`, starting with a letter or digit. Matched exactly (case-sensitive): `bos` and `BOS` are different stores |
| `name` | Required, at most 200 characters, no control characters |
| `city` | Optional, at most 100 characters; empty means no city |
| `category` | Required, at most 100 characters |
| `list_price` | The current catalogue price: a number of at least 0 with at most 2 decimals, e.g. `24.50` (no currency symbol, thousands separator or sign) |

A key that appears twice in one file is an error on the later rows ("Duplicate store code 'X' in
this file (line N)."). A stores or products file has at most **5,000** data rows.

### Sales

| Field | Rule |
| --- | --- |
| `store_code` | Code of an existing store of the current business, e.g. `BOS` (case-sensitive) |
| `receipt_number` | 1–40 characters |
| `sold_at` | ISO-8601 date-time. With an offset (`2026-09-01T14:30:00-04:00`, `2026-09-01T18:30:00Z`) it is used as is; without one (`2026-09-01T14:30:00`) it is a local time in the business's time zone. A local time that doesn't exist because of a daylight-saving change is an error; an ambiguous one (clocks going back) takes the earlier offset |
| `sku` | SKU of an existing product of the current business, e.g. `TOP-001` (case-sensitive) |
| `quantity` | Whole number greater than 0 |
| `unit_price` | Price actually charged per unit: a number of at least 0 with at most 2 decimals, e.g. `24.50` |

One row per **line item**. Rows with the same `store_code` and `receipt_number` form one receipt (a
sale); they don't need to be next to each other. All rows of a receipt must have the same `sold_at`
(compared as instants), and a SKU may appear only once per receipt (combine the quantities). A
receipt that already exists for that store is an error on each of its rows: receipts are never
overwritten or extended. A sales file has at most 50,000 data rows.

Imported receipts are ordinary sales: they appear on the dashboard, in Sales, Products and Stores,
and count as orders like any other receipt with line items.

## File format

- UTF-8 text (a byte-order mark is fine, as saved by Excel's "CSV UTF-8"), comma-separated, quoted
  per [RFC 4180](https://www.rfc-editor.org/rfc/rfc4180): a value may be wrapped in double quotes,
  and then may contain commas, line breaks and `""` for a literal quote. CRLF or LF line endings.
  Completely empty lines are ignored.
- The file name must end in `.csv`; at most 5 MB (HTTP 413 above).
- The first line is the header: the name of each column. Every data row must have as many values
  as the header ("Expected 6 values but found 5." otherwise). Values are trimmed of surrounding spaces.

A file that can't be read at all is refused with HTTP 400 before any row is checked: an empty file, a
file that isn't UTF-8 text, a name without `.csv`, broken quoting, no header, or a header with no
data rows.

## Templates

`GET /api/imports/templates/{kind}.csv` downloads the header of a kind in field order with two or
three example rows (`<kind>-template.csv`). The examples fit together: importing the stores
template, then the products template, then the sales template into an empty business succeeds
(3 stores, 3 products, 2 receipts with 3 line items, $164.10).

## Column mapping

A mapping says which column of the file feeds each field. It is a JSON object *field → column header*
sent with the upload as the form field `mapping`, for example

```json
{"code": "Store ID", "name": "Store name", "city": null}
```

- **No mapping** (the field omitted): each field reads the column with the same name, ignoring case
  and surrounding spaces, wherever it is in the file. That is how the templates import as they are.
- Column headers are compared after trimming spaces, otherwise exactly.
- `null` (or a field left out of an explicit mapping) means the field is not mapped.
- Columns that feed no field are ignored, so extra columns and any column order are fine.

The mapping is checked before any row is read. Each problem is a file-level error (no `line`) in a
normal `REJECTED` result, so the web app can show it next to the mapping:

| Problem | Message |
| --- | --- |
| A required field is not mapped | `Required field 'name' (Store name) is not mapped to a column.` (without a mapping: `... is not mapped: the file has no column named 'name'.`) |
| The header named in the mapping is not in the file | `Column 'Ville' (mapped to 'city') is not in the file.` |
| One column mapped to two fields | `Column 'Store ID' is already mapped to 'code'; a column can feed only one field.` |
| A field the kind doesn't have | `Unknown field 'colour' in the mapping; stores imports have the fields code, name, city.` |
| The header has the mapped name twice | `Column 'name' appears 2 times in the header; rename the copies so it can be mapped.` |

A `mapping` that is not a JSON object of strings or nulls is a 400.

### Preview and suggested mapping

`POST /api/imports/{kind}/preview` (multipart `file`) reads the file without validating or writing
anything and answers:

```json
{
  "kind": "stores",
  "fileName": "branches.csv",
  "rowCount": 12,
  "columns": ["Store ID", "STORE name", "Town", "Notes"],
  "sampleRows": [["S1", "Store 1", "City", ""]],
  "fields": [{"name": "code", "label": "Store code", "required": true, "description": "1 to 50 letters, ..."}],
  "suggestedMapping": {"code": "Store ID", "name": "STORE name", "city": "Town"}
}
```

`columns` are the header cells, trimmed: the values a mapping refers to. `sampleRows` are the first
10 data rows as written. `suggestedMapping` lists every field in template order with a column or
`null`. Suggestions compare headers ignoring case, spaces and punctuation ("Store ID", "store_id"
and "STORE-ID" are the same), first against the field names, then against common synonyms
("Store ID" → `code`, "Town" → `city`, "Price" → `list_price` or `unit_price`, "Date" → `sold_at`,
"Qty" → `quantity`, "Department" → `category`, ...). A column is suggested for at most one field.

## Modes (stores and products)

`mode` is a form field or query parameter:

| `mode` | New key | Key that exists in the business |
| --- | --- | --- |
| `create_only` (default) | created | row error: `Store code 'BOS' already exists. Choose “Create and update” to change it.` (`SKU 'X' ...` for products) |
| `create_or_update` | created | stores: `name` and `city` updated; products: `name`, `category` and `list_price` updated. A row identical to what is stored counts as **unchanged** |

- **Sale prices never change.** A sale item stores the price actually charged
  (`sale_items.unit_price`); updating a product's `list_price` changes only the catalogue price, so
  revenue figures for past periods stay exactly the same. Updates never touch sales at all.
- **Category is a current attribute.** Category reports group past sales by the product's category
  now, so a category change moves the product's past revenue to the new category. The result's
  `categoryChanges` (also in a dry run) says how many updated products change category.
- **An unmapped `city` is left as it is** on update (and a new store gets none); a mapped `city` that
  is empty clears it.
- Sales accept only `create_only` (anything else is a 400): receipts are only ever added.

Store codes and SKUs of **other businesses** play no part: a code that exists only elsewhere is new
here, and another business's stores and products are never updated.

## Validating and importing

`POST /api/imports/{kind}` (multipart `file`, optional `mapping`, `mode`, `dryRun` default `true`)
answers HTTP 200 with an `ImportResult`:

```json
{
  "batchId": null,
  "status": "VALIDATED",
  "dryRun": true,
  "fileName": "products.csv",
  "rowCount": 16,
  "saleCount": 0,
  "lineCount": 0,
  "totalAmount": 0.00,
  "errors": [],
  "errorCount": 0,
  "kind": "products",
  "mode": "create_or_update",
  "created": 12,
  "updated": 3,
  "unchanged": 1,
  "categoryChanges": 1
}
```

- `status`: `VALIDATED` (dry run without errors), `IMPORTED` (written; `batchId` is set) or
  `REJECTED` (nothing written; see `errors`).
- For sales, `created` = `saleCount` (receipts) and `updated` = `unchanged` = `categoryChanges` = 0;
  `saleCount`, `lineCount` and `totalAmount` are as before. For stores and products the sales fields
  are 0.
- For a rejected file the counts cover the rows without errors; for a file rejected by its mapping,
  its size or a failed write they are 0.
- Each error is `{ "line": 7, "field": "list_price", "column": "Price", "message": "..." }`:
  `line` is the **physical line** in the file (the header is line 1; a quoted value spanning several
  lines moves later line numbers accordingly), `field` the import field, `column` the file's own
  header feeding it. `line` is `null` for problems with the whole file (mapping, repeated file, size);
  `field` and `column` are `null` when no single value is concerned (e.g. a row with the wrong number
  of values). At most 100 errors are listed, by line; `errorCount` counts all of them.

A **dry run** runs in a read-only transaction and never writes. A **real import** validates again and,
only if there are no errors at all, writes everything in one transaction:

- **All or nothing**: one bad row, or any failure while writing, and nothing of the file is written.
- Imports into one business are **serialised** by a per-business advisory lock held for the
  transaction, so two uploads cannot both create the same code or SKU, or both pass the duplicate
  checks. If a constraint is still violated (data written meanwhile by something other than an
  import), the result is `REJECTED` with "The data changed while importing; try again. Nothing was
  imported." — never a 500. Any other database failure while writing is also a `REJECTED` result
  ("The import could not be completed; try again. ...") and is logged.
- **Duplicate files**: a file whose content (SHA-256 of the bytes) was already *imported* into the
  business **as the same kind** is rejected: "This file was already imported on 2026-10-02 (same
  content). Nothing was imported." (the date in the business's time zone). Rejected attempts don't
  count, so a corrected situation (say, the missing store now exists) lets the same file be retried.
  The same bytes may be imported as another kind, and into another business.

`POST /api/imports` (multipart `file`, `dryRun`) still works exactly as before: a sales file whose
header is the template header, in that order (case and surrounding spaces don't matter; a different
header is a 400), then the same as `POST /api/imports/sales` without a mapping. Its result has the
new fields too.

## Errors file

`POST /api/imports/{kind}/errors.csv` takes the same form fields as the import, validates as a dry
run (nothing is written or recorded) and downloads `<kind>-errors.csv`: one CSV row per error with
`line`, then the row's values under the file's **own** column headers in file order, then `field`,
`column` and `error`. File-level errors have an empty line and empty values. A file without errors
gives the header row only. All errors are included, not only the first 100. The file is written
with the reports' `CsvWriter`: text that a spreadsheet would run as a formula (starting with `=`,
`+`, `-`, `@`, tab or CR), in the values or in the file's headers, gets a leading `'`.

## History

Every real (non-dry-run) import attempt is recorded in `import_batches`, never previews, dry runs or
errors files, nor uploads refused with an HTTP error (unreadable file, malformed mapping or mode):

- `IMPORTED` with its counts (`created`, `updated`, `unchanged`; for sales also receipts, line items
  and total) and the member who uploaded it.
- `REJECTED` with its error count and **no data**: it is written after the rollback, in its own
  transaction.

`GET /api/imports?page=0&size=20&kind=products` lists the business's attempts, newest first
(`page` ≥ 0, `size` 1–100, `kind` optional: `sales`, `stores` or `products`, anything else is a 400):

```json
{ "page": 0, "size": 20, "totalItems": 2, "totalPages": 1, "items": [
  { "batchId": 7, "kind": "products", "mode": "create_only", "status": "REJECTED", "fileName": "products.csv",
    "rowCount": 1, "saleCount": 0, "lineCount": 0, "totalAmount": 0.00, "created": 0, "updated": 0,
    "unchanged": 0, "errorCount": 1, "importedBy": "Alex", "createdAt": "2026-10-02T11:05:00Z" } ] }
```

`importedBy` is the uploader's display name (`null` for imports made before it was recorded).
`GET /api/imports/{id}` returns one attempt of any kind with the same fields plus `firstSoldAt` and
`lastSoldAt` of a sales batch's receipts (`null` for other kinds and rejected attempts), or 404
"Import N was not found." (also for another business's attempt).

## Reports after an import

Stores, products and sales changes bump `report_data_version` in the same transaction (Flyway V11
triggers), so Cube-backed reports follow at once ([cube-reports.md](cube-reports.md)): a new store
is in the dashboard context, a new category is in the categories report (with 0 until it has sales),
and imported sales are in the very next report. The web app refreshes its reporting context after
every `IMPORTED` result.

## Sample file

[`sample-import.csv`](sample-import.csv) matches the demo business (stores BOS, CAM, PVD and WEB,
SKUs from the demo catalogue) and is dated after the demo data ends (2026-08-31), so it imports
cleanly into a freshly seeded demo database: 12 rows, 8 receipts, 12 line items, 15 units,
total $860.10. Importing it a second time is rejected as a duplicate file.
