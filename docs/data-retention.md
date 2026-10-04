# Data retention

What Insight Studio keeps, for how long, and what deleting an account or a business removes. The
periods below are the defaults; the settings that change them are listed at the end. Contract:
[account-management-contract.md](account-management-contract.md) §4.

## While the account and business exist

| Data | Kept | Then |
|---|---|---|
| Sales, products, stores, imports history, saved reports, charts, dashboards (and their revisions) | until deleted by an owner/admin or with the business | — |
| Audit events | 400 days | deleted by a daily purge |
| Sessions | 8 hours idle | purged every minute |
| Password-reset and email-verification tokens (SHA-256 only) | until used or expired (30 min / 24 h) + 7 days | deleted by a daily purge |
| Invitations (invitee email, role; token SHA-256) | open: until accepted, revoked or expired (7 days); closed: 400 days | deleted by a daily purge |
| Outgoing emails (`mail_outbox`) | body (with its link) until sent, failed or expired; the row without body 7 days | deleted |
| Rate-limit hits (SHA-256 of an email or IP, no clear text) | 1 day | deleted |
| Cube rollups (Cube Store) | rebuilt after every data change | superseded tables dropped by Cube at its next build once untouched for 1 hour |

Logs name ids (account, business, outbox, import), never passwords, tokens, links or email bodies.

## Deleting a business

An owner deletes a business after re-entering their password and typing its name. The preview lists
what will be removed and which other members lose access. In one transaction:

- every row of the business is **deleted** (hard delete, no soft-delete flag, no grace period): sales
  and sale items, products, stores, import history, saved reports, charts, dashboards and every
  revision, invitations, memberships, audit events, and the business itself;
- its pending emails (for example invitations not sent yet) are cancelled and their bodies erased;
- a Cube purge is queued, in the same transaction. Cleanup in Cube is **eventual**, not immediate
  and not bound to a fixed deadline; see "Cube" below.

Members' accounts are not affected; they lose access to the business immediately.

**Before deleting**, an owner can download the business export (ZIP of CSV and JSON files).

### Cube

Cube's rollups are shared by every business, so a deleted business's figures live inside shared
tables, and Cube 1.7 drops a superseded table only at the end of a later build, once nothing has
used it for `CUBEJS_TOUCH_PRE_AGG_TIMEOUT` (1 hour in `infra/compose.yaml`; Cube's default is 24
hours). The purge (a row in `cube_purge_requests`) therefore works in two phases:

1. **Rebuild**: every rollup in every relevant time zone (the scheduled zones, the deleted business's
   zone and every remaining business's zone) is rebuilt until it answers from data without the
   business.
2. **Sweep**: once `CUBE_PURGE_SWEEP_DELAY` (70 minutes) has passed since the deletion, one more
   forced rebuild makes Cube drop the superseded tables. The purge is then `DONE`.

When Cube and the API are healthy, the cleanup is therefore complete shortly after the sweep delay
(about 70 minutes; `CubePurgeIntegrationTest` checks the result against Cube Store itself). **During
an outage it takes longer**, with no fixed deadline:

- every attempt that fails (Cube down, a rebuild that does not finish in time) is recorded in the row
  (`attempts`, `last_error`, `next_attempt_at`) and retried, **without limit**, after 5 minutes,
  doubling up to 1 hour between attempts; from the 6th failure in a row each failure is logged as an
  error so it can be alerted on;
- the row lives in PostgreSQL, so restarts lose nothing; a worker that dies mid-purge loses its lease
  (15 minutes) and another instance, or the restarted one, takes the purge over;
- an API instance without a Cube connection never claims purges: they stay `PENDING` until an
  instance with Cube runs them (with no Cube at all, nothing of the business was ever in Cube).

Until the purge is done, the deleted rows remain only in superseded tables no query can reach: the
API answers `404` for a deleted business before it ever calls Cube, and the API is Cube's only client.
`CubePurgeRetryIntegrationTest` covers failures, backoff, restarts and lease takeover.


## Deleting an account

An account is deleted after re-entering its password and typing its email. It cannot be deleted while
it is the only owner of a business: make another member an owner, or delete the business, first.
In one transaction:

- it leaves every business (an audit event "account deleted" is written in each);
- open invitations sent to its email, and open invitations it sent, are revoked (with their pending
  emails); its reset and verification tokens are deleted; its pending emails are cancelled and their
  bodies erased;
- **every session** is ended, on every API instance;
- the account row becomes a **tombstone**: email, password hash, verification and sign-in dates are
  erased and the name becomes "Deleted account". The row's id stays because charts, dashboards,
  imports, saved reports, invitations and audit events of the businesses it worked in still point at
  it; they show "Deleted account". Nothing in the tombstone identifies the person.

The same email address can sign up again; the new account is unrelated to the tombstone.

**Before deleting**, the account owner can download their data (JSON: profile, memberships, what they
authored and the audit events they performed).

## Backups

Database backups are outside the application and follow the operator's backup retention (configure
it with your PostgreSQL host; keep it as short as your recovery needs allow, for example 30 days).
Deleted data disappears from backups when they expire. A restore from a backup taken before a
deletion brings the deleted data back: after a restore, repeat the deletions made since the backup.
The Cube Store volume holds only rollups that are rebuilt from PostgreSQL; it needs no backup.

## Settings

| Setting (environment variable) | Default | What |
|---|---|---|
| `insight.retention.audit-days` (`RETENTION_AUDIT_DAYS`) | 400 | audit events |
| `insight.retention.token-days` (`RETENTION_TOKEN_DAYS`) | 7 | reset and verification tokens, counted from use or expiry |
| `insight.retention.invitation-days` (`RETENTION_INVITATION_DAYS`) | 400 | closed invitations (open ones are never purged) |
| `insight.retention.cron`, `insight.retention.enabled` | daily 03:17 | when the purge runs (`-` or `false` turns it off) |
| `insight.cube-purge.sweep-delay` (`CUBE_PURGE_SWEEP_DELAY`) | `PT70M` | must exceed `CUBEJS_TOUCH_PRE_AGG_TIMEOUT` and `CUBEJS_DB_QUERY_TIMEOUT` |
| `insight.cube-purge.time-zones` (`CUBEJS_SCHEDULED_REFRESH_TIMEZONES`) | — | zones rebuilt besides the businesses' own |
| `server.servlet.session.timeout` | 8h | idle sessions |

| `insight.cube-purge.retry-delay`, `max-retry-delay`, `alert-after-attempts` | 5 min, 1 h, 6 | purge retries (never given up) |

An API instance without a Cube connection leaves purges pending for one that has it.
