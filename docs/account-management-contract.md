# Business settings, audit history, export and deletion: contract

Binding agreement between the integrator, the **backend** agent and the **frontend** agent. Change
it only through the integrator. It extends what exists and does not duplicate it:

- `PATCH /api/businesses/{id}` (OWNER: name, time zone) already exists: it gains `currency`.
- Members, roles, invitations, leaving and last-owner checks (`BusinessService`, `InvitationService`)
  already exist: they gain audit events, nothing else changes.
- Password change, sessions, rate limits, `SessionVersionFilter` and the mail outbox already exist and
  are reused (reauthentication is the existing password check; ending sessions uses Spring Session's
  principal index).

The public demo business can never be changed, exported or deleted through these endpoints (it has no
members; anonymous callers get `401`). A business the caller is not a member of is `404` everywhere.

## 1. Business settings

| Field | Who changes it | Rules |
|---|---|---|
| `name` | OWNER | existing rules (1–200 characters, trimmed, no control characters) |
| `timeZone` | OWNER | IANA zone (existing check) |
| `currency` | OWNER | ISO 4217, **only while the business holds no monetary data** (below) |

ADMIN and VIEWER read the settings (they already have name, currency and time zone in the session
context); only OWNERs change them. All three need a verified email (existing rule).

### Currency: never relabel money

Amounts are stored as plain numbers (`products.list_price`, `sale_items.unit_price`, import totals) in the
business's single currency. Changing `currency` would silently relabel all of them, so:

- A currency change is allowed only while the business has **no products and no sales** (stores,
  members, charts, dashboards and saved reports hold no amounts and do not block it).
- Otherwise `409` `"The currency can't change once the business has products or sales: their
  amounts are in XXX. Create a new business for another currency."` Nothing is converted, ever.
- Sending the current currency is a no-op (no audit event).

### Time zone: history is re-bucketed, never rewritten

Sales are stored as instants (`sold_at timestamptz`). Every report, chart, dashboard, saved report and
export computes days, weeks and months **in the business's current time zone at query time**. After a
change:

- No stored row changes. Totals over a range that covers all sales are unchanged.
- A sale near midnight can move to the previous or next local day — and, at a month or year boundary,
  to another month or year; daily, weekly and monthly figures and the dashboard's date range shift
  accordingly. Comparisons with the previous period follow the new buckets.
- Fixed dates in saved reports and charts (`2026-03-01`) are read as dates in the **new** zone;
  rolling presets ("last 30 days") resolve against today in the new zone.
- Cube engine: rollups are built per query time zone; a zone not in `CUBEJS_SCHEDULED_REFRESH_TIMEZONES`
  is built on first use and may answer `503` "being updated" for a while (existing behaviour).

Before saving, the UI shows the impact:

`GET /api/businesses/{id}/time-zone-preview?timeZone=Area/City` (OWNER) →
```json
{ "from": "America/New_York", "to": "Europe/Paris",
  "salesTotal": 10397, "salesChangingDay": 412, "salesChangingMonth": 9,
  "months": [ { "month": "2026-03", "revenueBefore": "251204.10", "revenueAfter": "251190.00",
                "ordersBefore": 1690, "ordersAfter": 1689 } ] }
```
`months` lists only months whose totals differ (at most 24, newest first). Amounts are strings with two
decimals, like the reports.

`PATCH /api/businesses/{id}` body `{name?, timeZone?, currency?}` → `200 BusinessResponse` (existing
shape). `GET /api/businesses/{id}/settings` (VIEWER+) →
`{id, name, slug, currency, timeZone, role, currencyChangeAllowed, currencyLockedReason | null,
createdAt}`.

## 2. Audit history

Table `audit_events` (V16). Written **in the same transaction** as the change. `details` is built from
an allowlist per action; it never holds passwords, password hashes, tokens, token hashes, links,
cookies, email bodies, CSV contents or file hashes.

| Action | Target | `details` |
|---|---|---|
| `business.created` | business | `{name, currency, timeZone}` |
| `business.renamed` | business | `{from, to}` |
| `business.time_zone_changed` | business | `{from, to}` |
| `business.currency_changed` | business | `{from, to}` |
| `business.exported` | business | `{}` |
| `member.invited` | invitation | `{email, role}` |
| `invitation.revoked` | invitation | `{email, role}` |
| `invitation.accepted` | user | `{role}` |
| `member.role_changed` | user | `{from, to}` |
| `member.removed` | user | `{role}` |
| `member.left` | user | `{role}` |
| `member.account_deleted` | user | `{role}` |
| `import.completed` / `import.rejected` | import | `{kind, mode, fileName, rows, created, updated, errors}` |
| `chart.created` / `chart.updated` / `chart.duplicated` / `chart.deleted` | chart | `{title, revision}` (`duplicated`: `{title, fromChartId}`) |
| `dashboard.created` / `dashboard.updated` / `dashboard.duplicated` / `dashboard.deleted` | dashboard | `{name, revision, widgetCount}` (`updated` adds `renamedFrom` when the name changed) |

Not audited: reads, runs of reports and charts, sign-in (per account, not per business), saved-report
changes (they are personal views of existing data; may be added later with the same mechanism).

`GET /api/businesses/{id}/audit?limit=50&before={eventId}&category=business|member|import|chart|dashboard`
(ADMIN+; VIEWER `403`) → `{ "events": [AuditEvent], "nextBefore": 123 | null }`, newest first,
`limit` 1–100. `AuditEvent` = `{id, action, actor: {id, name} | null, targetType, targetId, details,
createdAt}`; a deleted account's actor is `{id, name: "Deleted account"}` (no email).

## 3. Export

**Account export** — `GET /api/account/export` (signed in) → `application/json` attachment
`insight-studio-account-YYYY-MM-DD.json`:
`{exportedAt, account: {id, email, displayName, createdAt, emailVerifiedAt, lastSignInAt},
memberships: [{businessId, businessName, role, since}], authored: {charts: [{businessId, id, title}],
dashboards: [...], savedReports: [...], imports: [{businessId, id, kind, fileName, createdAt}]},
auditEvents: [events the account performed, as in §2, with businessId]}`. Never the password hash,
session data, tokens or other people's data beyond business names.

**Business export** — `GET /api/businesses/{id}/export` (OWNER, verified) → `application/zip`
attachment `insight-studio-{slug}-YYYY-MM-DD.zip` with UTF-8 CSV files (header row, RFC 4180, formula
injection guarded like the existing CSV exports) and one JSON file:

`business.json` (settings, exportedAt, format version 1), `members.csv`, `invitations.csv` (open and
past; no token data), `stores.csv`, `products.csv`, `sales.csv`, `sale_items.csv`, `imports.csv`,
`saved_reports.csv`, `charts.json` (current definitions), `dashboards.json` (current layouts),
`audit_events.csv`.

Rate limited (existing limiter: 5 business exports per business per hour, 5 account exports per account
per hour → `429`). The business export writes `business.exported`.

## 4. Deletion

Both need **reauthentication**: the body carries the account's current `password`, checked like the
existing password change (same rate limit; wrong → `400 "Your password is incorrect."`), plus a typed
confirmation. Both answer a **preview** first.

### Business deletion (OWNER, verified)

`GET /api/businesses/{id}/deletion-preview` →
`{business: {id, name}, counts: {members, pendingInvitations, stores, products, sales, imports,
savedReports, charts, dashboards, auditEvents}, otherMembers: [{userId, displayName, role}]}`.

`DELETE /api/businesses/{id}` body `{password, confirmName}` (`confirmName` must equal the business name,
exactly, after trimming) → `204`. In **one transaction**, after locking the business row:

1. Dashboards (refs, revisions), charts (revisions), saved reports, sale items, sales, import batches,
   products, stores, invitations (all, open or not), memberships and audit events of the business are
   deleted; then the business row. Hard delete: nothing is kept in the database.
2. Pending emails of the business (`mail_outbox.business_id`, e.g. invitations) become `EXPIRED` with
   their body erased.
3. One `cube_purge_requests` row (business id, time zone, `report_data_version` after the deletion).

The triggers bump the report data version, so no report can serve figures computed before. Every
member's sessions stay valid (they are per account) but the business is `404` for them; the UI moves
them to another business.

### Account deletion

`GET /api/account/deletion-preview` → `{account: {email, displayName}, memberships: [{businessId,
businessName, role, memberCount}], blockingBusinesses: [{businessId, businessName}], authoredContent:
{charts, dashboards, savedReports, imports}}`.

**Last-owner protection**: while the account is the only OWNER of any business, deletion is refused
(`409 "You are the only owner of …. Make another member an owner or delete the business first."`) and
the preview lists those businesses as `blockingBusinesses`.

`DELETE /api/account` body `{password, confirmEmail}` (`confirmEmail` equals the account email, case
ignored) → `204` and the session cookie cleared. In one transaction:

1. `member.account_deleted` in each business, then the memberships are deleted.
2. Open invitations **sent to** the account's email are revoked; password-reset and email-verification
   tokens are deleted; pending emails of the account (`mail_outbox.user_id`, or recipient equal to its
   email) become `EXPIRED` with the body erased.
3. **Every session** of the account is deleted (Spring Session principal index), and
   `session_version` is incremented so a session another instance holds in memory fails too.
4. The user row becomes a tombstone: `email`, `password_hash`, `email_verified_at`, `last_sign_in_at`
   `NULL`, `display_name` "Deleted account", `deleted_at` now. Charts, dashboards, imports, saved reports
   and invitations it created stay with their businesses and show "Deleted account". The email can be
   used to sign up again (a new, unrelated account).

### Cube

Rollups are shared by every business, so a deletion cannot drop "the business's rollup". The purge
worker (any instance with a Cube connection, lease like the mail outbox; retried until it succeeds, so
the cleanup is eventual) asks Cube to rebuild every rollup (`"cache": "must-revalidate"`) in every time
zone of `CUBEJS_SCHEDULED_REFRESH_TIMEZONES`, the deleted business's zone and the zones of the remaining
businesses, until each answers from a data version ≥ the request's. Superseded Cube Store tables are
then dropped by Cube itself; the backend agent determines and documents the exact Cube 1.7.46 behaviour
and settings (and the integrator sets them in `infra/`). The Cube integration test proves that after a
deletion no Cube Store rollup table contains the business id.

### Retention

`docs/data-retention.md` (integrator) states what is deleted when, what is kept (tombstones, finished
mail rows without bodies for 7 days, audit events 400 days, rate-limit rows 1 day) and backups (outside
the application: the operator's backup retention applies; deleted data disappears from them when they
expire).

## 5. Web app

- **Settings → Business** (`/settings/business`; ADMIN+ see it, read-only for ADMIN): name, time zone
  (with the preview of §1 before saving), currency (enabled only when `currencyChangeAllowed`, else the
  reason); OWNER also: "Export business data" and "Delete business" (preview, password, type the name).
- **Settings → Activity** (`/settings/activity`, ADMIN+): audit history, newest first, category filter
  (kept in the URL), "Load more", readable sentences ("Owner renamed the business from A to B").
- **Account page**: "Download your data" and "Delete account" (preview, blocking businesses with links
  to their members page, password, type the email). After deletion: the sign-in page with a notice.
- After deleting a business: the session context is refreshed and the app switches to another
  business, or to "Create a business" when none is left.
- Destructive dialogs: focus trapped, Escape cancels, the confirm button disabled until the typed
  confirmation matches; errors announced.

## 6. Ownership

| Owner | Files |
|---|---|
| **Integrator** | `apps/api/pom.xml`, `db/migration/**` (V16 done), this contract, `README.md`, `infra/**`, `docs/data-retention.md`, merges |
| **backend** agent | `apps/api/**` except the above; `services/analytics/**` only if Cube settings must change (say why); `docs/account-management-api.md` |
| **frontend** agent | `apps/web/**`; `docs/frontend-account-management.md` |
