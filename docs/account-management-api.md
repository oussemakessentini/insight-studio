# Business settings, audit history, export and deletion: API

How the API implements [account-management-contract.md](account-management-contract.md) (the binding
agreement). Schema: Flyway `V16__account_management.sql`. Everything below needs a signed-in session;
anonymous callers (public demo visitors included) get `401`, and a business the caller is not a member
of, the public demo business included, is `404`.

## Endpoints

| Endpoint | Who | Answer |
|---|---|---|
| `GET /api/businesses/{id}/settings` | VIEWER+ | `{id, name, slug, currency, timeZone, role, currencyChangeAllowed, currencyLockedReason, createdAt}` |
| `PATCH /api/businesses/{id}` `{name?, timeZone?, currency?}` | OWNER, verified | `200 BusinessResponse`; `409` currency locked |
| `GET /api/businesses/{id}/time-zone-preview?timeZone=Area/City` | OWNER | `{from, to, salesTotal, salesChangingDay, salesChangingMonth, months[]}` |
| `GET /api/businesses/{id}/audit?limit&before&category` | ADMIN+ (VIEWER `403`) | `{events: [AuditEvent], nextBefore}` |
| `GET /api/businesses/{id}/export` | OWNER, verified | `application/zip`, `insight-studio-{slug}-YYYY-MM-DD.zip` |
| `GET /api/businesses/{id}/deletion-preview` | OWNER, verified | `{business, counts, otherMembers}` |
| `DELETE /api/businesses/{id}` `{password, confirmName}` | OWNER, verified | `204` |
| `GET /api/account/export` | signed in | `application/json`, `insight-studio-account-YYYY-MM-DD.json` |
| `GET /api/account/deletion-preview` | signed in | `{account, memberships, blockingBusinesses, authoredContent}` |
| `DELETE /api/account` `{password, confirmEmail}` | signed in | `204`, session cookie cleared |

Code: `business/BusinessService` (settings, currency, preview), `business/MemberAccess` (the path's
business: 404/403/verified checks), `audit/*`, `accountdata/*` (exports, previews, deletions),
`cubepurge/*`, `retention/RetentionJob`.

### Settings

- **Currency** (`BusinessService.update`): ISO 4217 (`Currency.getInstance`), any case. Allowed only
  while the business has no row in `products` and none in `sales` (`hasMonetaryData`); otherwise `409`
  with the contract's message naming the current currency. The check and the update run under the
  business row lock. Sending the current currency changes nothing and writes no event; a refused
  currency change refuses the whole request (a rename in the same body is not applied).
- `currencyChangeAllowed` / `currencyLockedReason` describe the data rule only (the same for every
  role); only OWNERs may send the change.
- **Time zone preview** (`BusinessQueries.timeZonePreview`): two SQL statements over the business's
  orders (receipts with at least one item, as in every report; revenue `SUM(quantity × unit_price)`):
  counts of orders whose local date / local month (`sold_at AT TIME ZONE zone`) differ between the
  current and the new zone, and, per local month (`YYYY-MM`) in either zone, orders and revenue before
  and after, keeping only the months that differ (newest first, at most 24). Amounts are strings with
  two decimals. Nothing is changed; a missing or unknown zone is `400`.
- Changing the zone rewrites no row: every report buckets `sold_at` in the business's current zone at
  query time (`BusinessSettingsIntegrationTest` shows a 23:30 UTC sale moving to 1 April in Paris, the
  totals unchanged and the preview's counts matching the reports).

### Audit history

- `AuditLog.record(businessId, actor, AuditAction, targetId, details)` writes one `audit_events` row and
  fails outside a transaction, so every event is committed (or rolled back) with its change.
- `AuditAction` is the allowlist: each action's name, target type and the only keys its `details` may
  hold (contract §2 table). Any other key, or a value that is not text/number/boolean/null, throws:
  passwords, hashes, tokens, links, email bodies, CSV contents and file hashes cannot be stored.
- Hooks: `BusinessService` (created, renamed, time zone, currency, role changed, removed, left),
  `InvitationService` (invited, revoked, accepted), `BusinessDataService` (exported),
  `AccountDataService` (account deleted), `ImportService` (completed in the import's transaction;
  rejected in the transaction that records the rejected batch; dry runs are not audited),
  `ChartService` and `CustomDashboardService` (created, updated, duplicated, deleted).
- Paging: `id < before`, newest first, `limit` 1–100 (default 50); `nextBefore` is the last id shown
  when there is more. Categories: `business`, `member` (`member.*` and `invitation.*`), `import`,
  `chart`, `dashboard`; anything else is `400`.
- A deleted account's actor is `{id, name: "Deleted account"}`; no email is ever shown.

### Exports

- **Business ZIP** (`BusinessExport`), one `REPEATABLE READ` transaction (every file shows the same
  moment) that first writes `business.exported` (so `audit_events.csv` lists it): `business.json`
  (`formatVersion: 1`, `exportedAt`, settings), `members.csv`, `invitations.csv` (open and past, with a
  status; no token data), `stores.csv`, `products.csv`, `sales.csv`, `sale_items.csv`, `imports.csv`
  (no content hash), `saved_reports.csv`, `charts.json` and `dashboards.json` (current revisions),
  `audit_events.csv`. CSV: UTF-8, header row, RFC 4180, CRLF, text cells through the existing
  `report.CsvWriter` (formula-injection guard); instants in ISO-8601 UTC. Built in memory (fine at the
  current volumes: the demo's ~10 000 sales are well under a few MB).
- **Account JSON**: profile, memberships, what the account authored (charts, dashboards, saved
  reports, imports) and the audit events it performed (with `businessId`). The `email` of a person it
  invited is removed from those events' details (other people's data). No password hash, session or
  token.
- Rate limits (existing `RateLimiter`): `export:business` 5 per business per hour, `export:account` 5
  per account per hour → `429` with `Retry-After`.

### Deletion

Both deletions check the typed confirmation first (`400 "Type the business name exactly as shown to
confirm."` / `"Type your email address to confirm."`; a typo never counts as a failed password), then
reauthenticate with `AccountService.reauthenticate`: the password check of the password change, the
same `sign-in:email` (5 per 15 minutes) and `sign-in:ip` limits, failures counted outside any
transaction; wrong → `400 "Your password is incorrect."`, too many → `429`.

**Business** (`BusinessDataService.delete`): the configured public demo business (slug
`insight.demo.business-slug`, even if an operator gave it members) is refused with `409 "The public demo
business can't be deleted."` (its preview still answers). Then, in one transaction: lock the business row, check the caller
is still an OWNER, delete every business-scoped table children first
(`AccountDataQueries.BUSINESS_TABLES`: dashboard refs, revisions, dashboards, chart revisions, charts,
saved reports, sale items, sales, import batches, products, stores, invitations, memberships, audit
events) and then the business; expire its pending emails (`mail_outbox.business_id`: `EXPIRED`, body
`NULL`); insert a `cube_purge_requests` row with the business's zone and the `report_data_version`
after the deletion (the V11 triggers have bumped it). Other members keep their sessions; the business
is a `404` for them.

**Account** (`AccountDataService.delete`), one transaction: lock the user row and its businesses (id
order), `409` while it is the only OWNER of any business (`"You are the only owner of A, B. Make another
member an owner or delete the business first."`), `member.account_deleted` in each business, delete the
memberships, revoke open invitations sent to its email, delete its reset and verification tokens,
expire its pending emails (`user_id` or recipient = its email), delete **every** session row whose
`spring_session.principal_name` is `user:{id}` (the principal index the session repository sets), and
turn the row into a tombstone (`email`, `password_hash`, `email_verified_at`, `last_sign_in_at` `NULL`,
`display_name` "Deleted account", `deleted_at`, `session_version + 1`). The open invitations the
account **sent** are revoked too (`invitation.revoked` in their business, actor = the account, written
before it becomes a tombstone) and their pending invitation emails (same business, kind and recipient)
expire with their bodies erased. The preview reports them as `openInvitationsSent` (an additive field). The controller then signs the
request out (cookie cleared).

Tombstones everywhere: `UserQueries.findById`, `findByEmail` and `sessionVersion` ignore deleted users,
so sign-in, password reset, verification, invitations and `SessionVersionFilter` treat the account as
gone (a session an instance still holds fails its next request). Charts, dashboards, imports, saved
reports, invitations and audit events keep the user id and show "Deleted account". The unique index on
`lower(email)` ignores `NULL`, so the address can sign up again as a new account.

**Mail ownership**: from now on `MailOutbox.enqueue(..., businessId, userId)` records the owner:
invitation emails carry their business, reset/verification/existing-account emails their user
(`InvitationNotifier`, `PasswordResetNotifier` and `VerificationNotifier` take the id).

## Cube purge

Rollups are shared by every business (business id is a dimension), so a deletion cannot drop "the
business's rollup"; every rollup must be rebuilt without the business, and the superseded Cube Store
tables must go.

### What Cube v1.7.46 does (measured with `testsupport/CubeStack`)

Read in `@cubejs-backend/query-orchestrator` (`PreAggregationLoader.dropOrphanedTables`,
`PreAggregations.addTableUsed` / `updateLastTouch`) and confirmed against Cube Store:

1. Each rollup is one table **per query time zone** (`prod_pre_aggregations.<cube>_<rollup>_<content
   version>_<structure version>_<build time>`); each build creates a new table.
2. Superseded tables are dropped **only at the end of a build**: `dropOrphanedTables` lists every table
   of the schema and drops those not in the keep list. Nothing drops tables on a timer: with no further
   build, a superseded table holding the deleted rows stayed for as long as we watched (13 minutes), even
   with very short timeouts.
3. With `CUBEJS_DROP_PRE_AGG_WITHOUT_TOUCH=true` (the default) and once the refresh worker has reached
   the end of a pass, the keep list is: tables **used** by a query within `CUBEJS_DB_QUERY_TIMEOUT`
   (default 10 minutes; the "used" key's lifetime is the query timeout), tables **touched** (served or
   refreshed) within `CUBEJS_TOUCH_PRE_AGG_TIMEOUT` (default **24 hours**), and the table just built.
   The refresh worker touches the current table of every scheduled zone on every pass.
4. With the defaults, a forced rebuild 80 seconds after a deletion dropped nothing (48 tables, the 12
   holding the deleted business among them). With `CUBEJS_TOUCH_PRE_AGG_TIMEOUT=20` and
   `CUBEJS_DB_QUERY_TIMEOUT=30s`, a rebuild after both had passed dropped all 24 superseded tables that
   held it.
5. `"cache": "must-revalidate"` right after a change may still answer from the previous build (the
   refresh key's one-second reuse): the first revalidation after the deletion answered version 11 while
   the database was at 16. Asking again until the answer's `data_version` is new enough works (1.5 s in
   the test). Zones not in `CUBEJS_SCHEDULED_REFRESH_TIMEZONES` are only rebuilt when queried.

### The worker (`CubePurgeWorker`)

Every API instance runs it (`insight.cube-purge.enabled`, default on); requests are claimed one at a
time with `FOR UPDATE SKIP LOCKED` and a lease (`locked_until`), like the mail outbox.

1. **Rebuild** (as soon as the request is due): for each zone in `insight.cube-purge.time-zones`
   (default: `CUBEJS_SCHEDULED_REFRESH_TIMEZONES`), the deleted business's zone and every remaining
   business's zone, ask each rollup (`orders.daily_by_store`, `order_categories.daily_by_store_category`,
   `order_products.daily_by_store_product`, then `line_items.daily_by_store_category`) with
   `must-revalidate` and a token for a remaining business (its marker rows carry `data_version`) until
   the answer's version is at least the request's. `line_items` has no version measure and is asked
   last, once the shared refresh key has moved on. Each zone has `zone-timeout` (2 minutes).
2. **Sweep** (once `sweep-delay` has passed since the deletion, default 70 minutes): bump `report_data_version` (a
   harmless change: every cache and rollup is simply rebuilt once) and rebuild everything again as in
   step 1. These builds drop every table that was neither used nor touched since the timeouts, i.e.
   every table built before the deletion. The request is then `DONE`.

Cleanup is **eventual**: there is no fixed deadline while Cube or the API is unavailable.

- A failed phase is retried **until it succeeds**: after `retry-delay` (5 minutes), doubling after each
  further failure up to `max-retry-delay` (1 hour). Each attempt is recorded in the row (`attempts`,
  `last_error`, `next_attempt_at`); from `alert-after-attempts` (6) failures in a row every failure is
  logged at `ERROR`. The statuses `FAILED` and `SKIPPED` (allowed by V16) are no longer written.
- Everything is in the row, so a restart loses nothing; a worker that dies mid-purge keeps its claim
  only until its `lease` (15 minutes) ends, then any instance takes it over.
- An instance without a Cube connection never claims requests; they stay `PENDING` for an instance
  that has one.

`cubepurge/CubePurgeRetryIntegrationTest`: failures persisted with capped backoff and never given up,
a restarted instance finishing a purge that failed before (both phases), a failed sweep retried, a
crashed instance's purge taken over when its lease ends, and an instance without Cube leaving purges
pending.

### Settings the integrator must add (`infra/compose.yaml`, Cube service and refresh worker)

| Variable | Value | Why |
|---|---|---|
| `CUBEJS_TOUCH_PRE_AGG_TIMEOUT` | `3600` | superseded tables become droppable 1 h after their last use instead of 24 h |
| `CUBEJS_DROP_PRE_AGG_WITHOUT_TOUCH` | `true` | the default, made explicit: drop by touch/use, not by "latest per structure" |
| `CUBEJS_DB_QUERY_TIMEOUT` | `10m` (default) | also the lifetime of the "used" mark; keep below `sweep-delay` |
| API: `CUBE_PURGE_SWEEP_DELAY` | `PT70M` (default) | must exceed both values above |
| API: `CUBEJS_SCHEDULED_REFRESH_TIMEZONES` | same as Cube's | read by the API as `insight.cube-purge.time-zones` |

A shorter touch timeout also means a zone not listed in `CUBEJS_SCHEDULED_REFRESH_TIMEZONES` and not
queried for an hour is dropped at the next build and rebuilt on its next query (the existing "being
updated" `503` while it builds). No change to `services/analytics` was needed.

### Proof

`cubepurge/CubePurgeIntegrationTest` (Cube stack, `CUBEJS_TOUCH_PRE_AGG_TIMEOUT=20`,
`CUBEJS_DB_QUERY_TIMEOUT=30s`, `sweep-delay` 45 s): three businesses (Paris, Tokyo, and the deleted one
in Tokyo, a zone the refresh worker does not build) query their Cube reports; the owner deletes the
business through the API; once the purge is `DONE`, every table of Cube Store's pre-aggregation schema
(`information_schema.tables`) is counted with `WHERE <cube>__business_id = <deleted id>`: all zero, and
none of the tables from before the deletion is left; the other businesses' reports are byte for byte
the same. Cube Store is queried with Cube's own driver inside the Cube container
(`CubeStack.businessRowsPerTable`), as the test classpath has no MySQL driver.

## Retention purge (`RetentionJob`)

Daily (`insight.retention.cron`, default `0 17 3 * * *`; `-` disables), on every instance (idempotent):

| Property | Default | Deletes |
|---|---|---|
| `insight.retention.audit-days` (`RETENTION_AUDIT_DAYS`) | `400` | audit events older than that |
| `insight.retention.token-days` (`RETENTION_TOKEN_DAYS`) | `7` | password-reset and email-verification tokens used or expired that long ago |
| `insight.retention.invitation-days` (`RETENTION_INVITATION_DAYS`) | `400` | invitations closed (accepted, revoked or expired) that long ago; open ones are never purged |

`insight.retention.enabled=false` turns it off on an instance. Finished mail rows keep their existing
7-day purge (`insight.mail.outbox.retention`), rate-limit rows their 1-day purge.

## Tests

- `accountdata/AccountManagementPermissionsIntegrationTest`: every endpoint × OWNER, ADMIN, VIEWER,
  unverified OWNER, member of another business (`404`), signed out (`401`), the public demo business.
- `business/BusinessSettingsIntegrationTest`: settings, currency rules, time zone change and preview.
- `audit/AuditIntegrationTest`: every action through the API, details, paging, categories,
  isolation, and a scan of every stored `details` for passwords, hashes, tokens, links and file content.
- `accountdata/ExportIntegrationTest`: ZIP entries, CSV rows equal to the database, isolation, no
  secrets, formula guard, account JSON, rate limits.
- `accountdata/DeletionIntegrationTest`: business and account deletion end to end (tables, isolation,
  mail, purge request, sessions over real HTTP, tombstone, sign-up again, last owner, wrong password and
  its rate limit, wrong confirmation).
- `retention/RetentionIntegrationTest`, `mail/MailOwnershipIntegrationTest`,
  `cubepurge/CubePurgeIntegrationTest` (Cube stack).

## Limitations

- The currency check and a concurrent product or sale insert are not serialized (the business row lock
  does not block inserts); a product created in the same instant as a currency change could be labelled
  with the new currency.
- The business ZIP is built in memory; very large businesses would need a streamed response.
- The account export leaves out invitees' emails in its audit events; the business export (OWNER) keeps
  them, like the members and invitations pages.
- The sweep bumps `report_data_version`, so every Cube rollup is rebuilt once per deleted business.
- Backups are outside the application (docs/data-retention.md).
