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
- a Cube purge is queued. Cube's rollups are shared by every business, so the business's figures
  live inside shared tables. A worker on the API asks Cube to rebuild every rollup in every relevant
  time zone (the scheduled zones, the deleted business's zone and every remaining business's zone)
  until each answers from data that no longer contains the business. Cube 1.7 drops a superseded
  table only at the end of a later build, and only once nothing has used it for
  `CUBEJS_TOUCH_PRE_AGG_TIMEOUT` (1 hour in `infra/compose.yaml`; Cube's default is 24 hours). So
  after `CUBE_PURGE_SWEEP_DELAY` (70 minutes) the worker forces one more rebuild, which drops them.
  **Within about 70 minutes of a deletion no Cube Store table holds the business's rows**; until
  then they sit only in superseded tables that no query can reach (the API answers `404` for a
  deleted business before calling Cube). `CubePurgeIntegrationTest` checks this against Cube Store
  itself. Without Cube the purge is skipped.

Members' accounts are not affected; they lose access to the business immediately.

**Before deleting**, an owner can download the business export (ZIP of CSV and JSON files).

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

Every API instance that runs the purge worker needs the Cube connection (`INSIGHT_CUBE_URL`), or set
`insight.cube-purge.enabled=false` on it; an instance without Cube marks the purges it claims as
skipped.
