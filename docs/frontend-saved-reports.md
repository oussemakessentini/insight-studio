# Web app: saved reports and PDF export

How `apps/web` implements `docs/saved-reports-contract.md`. The server is authoritative for every
check; the UI only hides what the user can't do and shows the server's message when it refuses.

## Routes

| Path | Route | Page |
|---|---|---|
| `/reports` | `reports` | Reports page: adds **Export PDF**, **Saved reports** and **Save report** |
| `/reports/saved` | `savedReports` | `pages/SavedReportsPage.tsx` |
| `/reports/saved/{id}` | `savedReport` | `pages/SavedReportPage.tsx` (keyed by id) |

Both new routes keep the **Reports** sidebar item active (`sectionOf`); there is no new sidebar
entry. The list is reached from the Reports page (a "Saved reports" button next to the report tabs)
and from the breadcrumbs.

## API client

- `api/savedReports.ts`: `SavedReport`, `SavedReportRange` (request: `{type:'fixed', from, to}` or
  `{type:'relative', preset}`), `SavedReportRangeResponse` (response: all four keys, unused ones
  `null`), `RelativePreset` + `RELATIVE_PRESETS` / `PRESET_LABELS` (contract §2), `SavedReportRun`
  (`savedReport` plus exactly one of `monthly` / `categories`), and `savedReportsApi`
  `list / get / create / update (PUT) / remove / run / exportUrl(id, 'csv' | 'pdf')`.
  `savedReportInput(report)` rebuilds a full PUT body, so a rename sends every other field unchanged.
- `api/client.ts`: new `putJson`.
- `api/reports.ts`: `pdfUrl(kind, filter)` next to `csvUrl` (same `from`, `to`, `storeId` query).
- All downloads go through `downloadFile` (fetch with `X-Business-Id`), so they work for any
  selected business. The filename comes from the server's `Content-Disposition`.

## Components

- `components/reports/ExportLink.tsx` (was `ExportCsvLink`): `ExportLink` with `format: 'csv' | 'pdf'`
  and `ExportLinks` (both side by side). Busy label "Exporting…", the server's error under the button.
  The CSV link behaves exactly as before (same URL, same accessible name "Export … as CSV").
- `components/Dialog.tsx`: modal on the native `<dialog>` (`showModal`: focus trap, inert page,
  Escape, focus returns to the opener). Content mounts only while open, so forms start fresh.
- `components/reports/SavedReportForm.tsx`: name, report kind, store, dates.
  - `mode="create"` (Save report): kind and store are a read-only summary of what the page shows;
    dates are **These dates** (the page's from/to, saved as `fixed`) or **A rolling period** with a
    preset select (preselected from the page's filter: 7/30/90 days, else "Last 30 days").
  - `mode="edit"` (saved report page): kind and store selects; **Fixed dates** with from/to inputs
    (prefilled with the current resolved period when switching from a rolling one) or a preset.
  - Client checks mirror the server: name 1–120 characters, both dates, `from <= to`, at most
    1098 days (`ReportingContext.MAX_RANGE_DAYS`). Server errors (400, 404 store, 409 duplicate
    name, 403) are shown as the problem-detail text.
- `components/reports/SaveReportDialog.tsx`: after saving, shows the name and the **resolved period
  returned by the server** ("Today it covers Last 30 days · Sep 2 – Oct 1, 2026"), with links to the
  saved report and to the list.
- `lib/savedReports.ts`: `KIND_LABELS`, `rangeLabel`, `rangeDescription` ("Previous quarter ·
  Jul 1 – Sep 30, 2026"), `storeLabel`, `savedReportPermissions`.

## Pages

- **Saved reports list**: name (link), report, dates (resolved period over the range label), store,
  last updated (business time zone, "by" creator). On phones the hidden columns move under the name.
  ADMIN+: inline **Rename** (PUT with the new name; Enter saves, Escape cancels) and **Delete** with an
  inline confirmation, like Members. Empty state explains how to save one; VIEWERs read that owners
  and admins create them.
- **Saved report**: title = name; details line: kind · range label · resolved period · store · time
  zone. The table is the existing `MonthlyReportTable` / `CategoryReportTable` from
  `GET /api/saved-reports/{id}/report`, with CSV and PDF exports of the saved endpoints. ADMIN+:
  **Edit** (dialog, PUT; the report re-runs afterwards) and **Delete** (inline confirmation, then back
  to the list). `404` shows "Saved report not found"; failed writes show the server's message.

## Permissions

```ts
savedReportPermissions(access) = {
  available: access.role !== 'DEMO',
  canManage: (role OWNER | ADMIN) && access.emailVerified && !access.readOnly,
  needsVerification: (role OWNER | ADMIN) && !access.emailVerified,
}
```

Derived from the role rather than `canManageCatalog` (which today means the same thing) so the rule
stays the saved-reports rule (`require(Role.ADMIN)`, verified email) if the catalogue permission ever
changes.

| Who | Reports page | Saved reports |
|---|---|---|
| Public demo | CSV + PDF export; no Saved reports / Save report controls | `/reports/saved*` shows "Saved reports need an account" with Sign in |
| VIEWER | CSV + PDF, Saved reports button | open, run, export |
| OWNER / ADMIN, unverified | "Save report" opens an explanation with "Send a new link" | open, run, export; a note explains verification is needed to manage |
| OWNER / ADMIN, verified | Save report | open, run, export, rename, edit, delete |

## For the integrator to verify against the real backend

- `GET /api/saved-reports/{id}/report` answers `{savedReport, monthly}` or `{savedReport,
  categories}` (the key absent, not `null`, for the other kind; `null` would also work since the UI
  checks truthiness of `monthly`).
- `range` in responses carries `type`, `preset`, `from`, `to` (unused ones `null`); requests send only
  the keys of their type.
- `createdBy` may be `null` (the UI tolerates it); `updatedAt`/`createdAt` are ISO instants.
- `storeName` is set whenever `storeId` is (the UI falls back to "Store N").
- 404 for unknown/foreign ids on `/report` (drives the not-found page); 409 on duplicate names with a
  problem detail.
- Export endpoints send `Content-Disposition` with a filename (otherwise the fallback is
  `monthly-report.pdf` etc.).
