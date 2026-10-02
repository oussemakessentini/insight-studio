# Stores, products and sales imports with column mapping: contract

Binding agreement between the integrator, the **backend** agent and the **frontend** agent. Change
it only through the integrator. The existing sales import (`docs/csv-import.md`) keeps working,
including `POST /api/imports` exactly as today.

## 1. Import kinds and fields

| Kind | Key (within the business) | Fields (CSV template header order) |
|---|---|---|
| `stores` | `code` | `code` (required, 1–50 of `A-Z a-z 0-9 . _ -`), `name` (required, ≤ 200), `city` (optional, ≤ 100) |
| `products` | `sku` | `sku` (required, same charset, ≤ 50), `name` (required, ≤ 200), `category` (required, ≤ 100), `list_price` (required, decimal ≥ 0, ≤ 2 decimals) |
| `sales` | (`store_code`, `receipt_number`) | `store_code`, `receipt_number`, `sold_at`, `sku`, `quantity`, `unit_price` — every rule of `docs/csv-import.md` unchanged |

Field rules for stores and products are exactly the ones `POST /api/stores` and `POST /api/products`
apply today (reuse that validation; same messages where they exist). Store codes and SKUs are
matched exactly (case-sensitive), as the sales import matches them.

## 2. Modes (stores and products only)

| `mode` | Row whose key is new | Row whose key exists in the business |
|---|---|---|
| `create_only` (default) | created | **row error**: "Store code 'X' already exists. Choose “Create and update” to change it." (products: "SKU 'X' …") |
| `create_or_update` | created | updated: stores `name`, `city`; products `name`, `category`, `list_price`. Identical rows count as **unchanged** |

- Sales imports accept only `create_only` (anything else: `400`).
- **Updates never touch sales**: `sale_items.unit_price` (the price charged) is never changed, so
  historical revenue is unchanged by a new `list_price`. A changed `category` moves the product's past
  sales to the new category in category reports (category is a current attribute); the dry run and
  the result say how many updated products change category, and the UI warns about it.
- An optional field (`city`) that is **not mapped** is left as it is on update; mapped and empty sets
  it to empty (`null`).
- A key appearing twice in one file is an error on the later row(s) ("Duplicate store code 'X' in
  this file (line N)").

## 3. Column mapping

Mapping is stateless: the browser keeps the file and sends it with every request.

- `mapping` is a JSON object *field → source column header* (exact header text), sent as a multipart
  part or form field `mapping`, e.g. `{"code":"Store ID","name":"Store name","city":null}`.
- Omitted → identity: each field maps to the header of the same name (today's behaviour).
- Validation of the mapping itself (before any row): every required field mapped; a header named in
  the mapping must exist in the file; one source column may feed only one field; unknown field names
  rejected. Problems are file-level errors in a normal `REJECTED` result (not HTTP 400), so the UI can
  show them next to the mapping. Unmapped extra columns are ignored.
- Errors always name both the **field** and the **source column** (§4).

## 4. API (ADMIN or OWNER of the current business, verified email — `require(Role.ADMIN)` — everywhere)

`{kind}` is `sales`, `stores` or `products`; anything else is `404`.

| Method & path | Answer |
|---|---|
| `GET /api/imports/templates/{kind}.csv` | Template: header row in the order of §1 plus 2–3 example rows (sales examples use the store/product examples' codes and SKUs). `Content-Disposition: attachment; filename="<kind>-template.csv"` |
| `POST /api/imports/{kind}/preview` (multipart `file`) | `200 {kind, fileName, rowCount, columns:[header…], sampleRows:[[cell…]…] (first 10), fields:[{name, label, required, description}], suggestedMapping:{field: header|null}}`. Suggestions: case/space/punctuation-insensitive match on field names and common synonyms (e.g. "Store ID"→`code`, "Price"→`list_price`, "Date"→`sold_at`). Unreadable file / no header → `400` |
| `POST /api/imports/{kind}` (multipart `file`, `mapping`?, `mode`?, `dryRun` default `true`) | `200 ImportResult` (below) |
| `POST /api/imports/{kind}/errors.csv` (same parts as above) | The rejected rows as CSV: `line`, then the file's original columns in file order, then `field`, `column`, `error` (one CSV row per error; formula-injection-safe via `CsvWriter`). `Content-Disposition: attachment; filename="<kind>-errors.csv"`. A file without errors → header row only |
| `POST /api/imports` (legacy) | Unchanged: equals `POST /api/imports/sales` with identity mapping |
| `GET /api/imports?page&size&kind?` | History, newest first; `kind` filters |
| `GET /api/imports/{batchId}` | One batch (any kind), `404` for another business's or unknown id |

`ImportResult` keeps every current field and adds:

```json
{ "kind": "products", "mode": "create_or_update",
  "created": 12, "updated": 3, "unchanged": 1, "categoryChanges": 1,
  "errors": [{ "line": 7, "field": "list_price", "column": "Price", "message": "…" }] }
```

- For sales, `created` = receipts created (`saleCount`), `updated` = `unchanged` = 0. For stores and
  products, `saleCount`, `lineCount`, `totalAmount` are 0.
- `ImportError` gains `field` (target field or `null`); `column` stays the **source** column header.
- `status`: `VALIDATED` (dry run, no errors), `IMPORTED`, `REJECTED` (errors; nothing written).

History items (`ImportBatchSummary`, `ImportDetailResponse`) add `kind`, `mode`, `status`
(`IMPORTED` | `REJECTED`), `created`, `updated`, `unchanged`, `errorCount`, `importedBy` (display name
or `null`). Existing sales fields stay (0 for catalogue kinds); the sales date range in the detail
stays sales-only (`null` otherwise).

## 5. Atomicity, duplicates, history, limits

- **All or nothing** per file and per kind: one transaction; any error → nothing written. A
  per-business advisory lock serialises imports of one business, so two concurrent imports cannot
  both create the same code/SKU; a constraint violation that still happens becomes a `REJECTED`
  result ("The data changed while importing; try again."), never a 500.
- **Duplicate files**: a file whose SHA-256 was already **imported** for the same business and kind
  is rejected ("This file was already imported on …"). Rejected attempts don't block a retry
  (Flyway V13: partial unique index on `(business_id, kind, content_sha256) WHERE status='IMPORTED'`).
- **History**: every non-dry-run attempt is recorded in `import_batches` (V13 adds `kind`, `mode`,
  counts, `error_count`, `created_by`, status `REJECTED`): `IMPORTED` with its counts, or `REJECTED`
  with its error count and **no data**. The rejected record is written after the rollback, in its own
  transaction. Dry runs, previews and error CSVs are never recorded.
- **Limits** (unchanged): 5 MB per file (413 above), 50,000 data rows, 100 errors listed (all counted),
  file name ≤ 255. Stores and products files: at most 5,000 rows (a clear file error above).
- **Isolation**: everything runs in the business resolved by `CurrentBusiness`; codes and SKUs are
  looked up only in that business; history and batch ids of another business are `404`/invisible.
- **Freshness**: stores, products and sales changes bump `report_data_version` (V11 triggers), so Cube
  reports follow; the web app refreshes the reporting context (store list, data range, categories)
  after every `IMPORTED` result of any kind.

## 6. Web app

Imports page (`/imports`), ADMIN+ as today:

1. Choose the type (Sales, Stores, Products); download its template.
2. Choose a file → preview (first rows) with one select per field, prefilled from
   `suggestedMapping`, required fields marked; for stores/products choose the mode (default "Create
   new only"; "Create and update existing" explains that sale prices are never changed and that a
   category change moves past sales between categories).
3. Validate (dry run) → errors table (line, field, column, message) and **Download errors CSV**;
   counts (to create / update / unchanged, category changes).
4. Import → result; on `IMPORTED` call `refreshContext()`.
5. History: type, outcome (Imported / Rejected), counts, who, when; filter by type; detail page per
   batch for every kind.

Desktop and 390 px mobile without page overflow; accessible labels for every mapping select.

## 7. Ownership

| Owner | Files |
|---|---|
| **Integrator** | `apps/api/pom.xml`, `db/migration/**` (V13 done), this contract, `README.md`, merges |
| **backend** agent | `apps/api/**` except the above; `docs/csv-import.md` (extend it for all kinds) |
| **frontend** agent | `apps/web/**`; `docs/frontend-imports.md` |
