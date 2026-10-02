# Web app: CSV imports of sales, stores and products

How `apps/web` implements §6 of `docs/catalog-imports-contract.md`. The API is authoritative for
every rule; the browser only previews, checks the obvious and explains.

## Route and access

`/imports` (and `/imports/:id`) for `context.access.canImport` (OWNER, ADMIN), as before. The type
lives in `?type=sales|stores|products` (sales, the default, is omitted from the URL), so refresh,
shared links and back/forward restore it.

## Flow (`pages/ImportsPage.tsx`, `components/imports/`)

1. **Type** (`ImportTypePicker`): a radio group of Sales / Stores / Products and a
   *Download … template* button (`GET /api/imports/templates/{kind}.csv`). Disabled while a request
   runs.
2. **File** (`ImportFlow`, keyed by type: changing type drops file, mapping and results). Choosing a
   file over 5 MB is refused in the browser; otherwise `POST /api/imports/{kind}/preview`. A `400`
   (unreadable file, no header) is shown under the file input.
3. **Match columns** (`ColumnMapping`): the first rows (horizontally scrollable, each header shows the
   field it feeds) and one labelled select per field, listing the file's columns plus "Choose a
   column…" (required fields, label suffixed "(required)") or "Not mapped" (optional). Prefilled from
   `suggestedMapping` (suggestions naming a missing column are ignored). The browser flags a column
   used twice at once and an unmapped required field on Validate (focus moves to it); file-level API
   errors that name a field (`line: null`, `field` set) appear under that field's select.
   Stores/products choose the mode: **Create new only** (default) or **Create and update existing**,
   whose text says that sale prices are never changed and (products) that a category change moves
   past sales to the new category.
4. **Validate** (`dryRun=true`) → counts (sales: rows, receipts, line items, total; stores/products:
   rows, to create, and in update mode to update and unchanged), a category-change note for products,
   or the errors table (line, field label, source column, message; "N more errors not listed" when
   `errorCount` exceeds the list) with **Download errors CSV** (`POST /api/imports/{kind}/errors.csv`
   with the same parts, saved through `downloadForm`). Changing the mapping or the mode drops the
   validation.
5. **Import** (`dryRun=false`) → success with links (the batch; sales: "View sales on these dates";
   others: their list page) or the rejection (recorded in the history). On `IMPORTED`, for every kind,
   the page calls `refreshContext()` and reloads the history.

Requests send `file`, `mapping` (JSON of every field → header or `null`), `mode` (stores/products
only) and `dryRun` as multipart form fields.

## History and detail

`ImportHistory`: type filter (`?kind=` on `GET /api/imports`, page reset on change), columns file,
type, outcome badge (Imported / Rejected), result (sales: receipts and total; others: created /
updated / unchanged; rejected: error count), imported by, when. On phones type, date and result move
under the file name.

`ImportDetailPage` works for every kind: type, outcome, mode, who and when, per-kind tiles; sales keep
first/last sale and the Sales link; stores/products link to their list; rejected attempts say nothing
was written and link back to the import page for that type.

## Downloads

`api/client.ts` gains `downloadForm(path, form, fallbackName)`, a multipart POST saved as a file; it
shares `downloadFile`'s Content-Disposition and blob handling. `DownloadButton` shows "Downloading…" /
"Preparing CSV…" and any failure next to the button.
