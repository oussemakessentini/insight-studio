# Backup and restore

PostgreSQL holds everything (Cube Store only holds rollups rebuilt from it, so it needs no backup). Retention
of the data itself: [data-retention.md](data-retention.md).

## Backup

`infra/backup/backup.sh` writes, for one moment in time:

- `insight-<UTC timestamp>.dump` — `pg_dump` custom format (compressed, restorable table by table);
- `….dump.sha256` — its checksum;
- `….manifest` — what it holds: Flyway version, the exact row count of every table and a revenue checksum.

The dump and the manifest are read from **the same snapshot** (a `REPEATABLE READ` transaction exports it and
`pg_dump --snapshot` uses it), so they agree however busy the database is.

```bash
DB_CONTAINER=insight-studio-db-1 POSTGRES_DB=insight_studio POSTGRES_USER=insight \
BACKUP_DIR=/var/backups/insight BACKUP_KEEP_DAYS=14 infra/backup/backup.sh
```

Schedule it daily (cron, systemd timer) and **copy `BACKUP_DIR` off the host** (another machine, object
storage with versioning): a backup on the database's disk does not survive losing that disk. Encrypt copies
that leave the host (they contain customer data). Keep at least one backup per release until the release is
accepted ([deployment.md](deployment.md)).

## Restore drill (verify every backup is usable)

`infra/backup/restore-drill.sh [dump]` (default: the newest in `BACKUP_DIR`) checks the SHA-256, restores into a
**throwaway** PostgreSQL container (never the live database), then compares with the manifest: Flyway version,
revenue checksum and every table's row count. Any difference, or a table missing from the comparison, fails
the drill. Run it after each backup (or at least weekly) and before every upgrade.

Recorded drill (production compose stack with the demo data set, backup taken while the API was running):

```
insight-20261004T143923Z.dump: OK
Restored in 10 s
  ok    flyway_version = 20
  ok    revenue_checksum = 1555513.20
  ok    rows of sales = 10397
  ok    rows of sale_items = 17993
  … (32 tables)
RESTORE DRILL PASSED: 32 tables, Flyway version and revenue match the backup
```

The same drill against a manifest altered by one row (`rows.sales=10398`) failed as it should:
`FAIL rows of sales: backup had 10398, restore has 10397`.

## Restore (real incident)

1. Stop the application so nothing writes: `docker compose -f infra/compose.prod.yaml --env-file infra/.env.prod stop api web`.
2. Choose the backup; run the restore drill on it first.
3. Keep the current database aside: `docker compose ... exec db pg_dump -U $POSTGRES_USER -Fc $POSTGRES_DB > before-restore.dump`.
4. Recreate the database and restore:
   ```bash
   docker compose ... exec db dropdb -U $POSTGRES_USER $POSTGRES_DB
   docker compose ... exec db createdb -U $POSTGRES_USER $POSTGRES_DB
   docker compose ... exec -T db pg_restore -U $POSTGRES_USER -d $POSTGRES_DB --no-owner --exit-on-error < insight-<timestamp>.dump
   ```
5. Start the images **of the backup's release or later** (the Flyway version in the manifest; a newer release
   migrates forward on start).
6. Data written after the backup is lost: deletions made since then (businesses, accounts) are back and must
   be repeated (data-retention.md, "Backups"); payment-provider state (subscriptions) is re-synchronised by the
   next webhook of each subscription.

Recovery point: the last backup (daily: up to 24 h of data). For less, add PostgreSQL WAL archiving
(point-in-time recovery) with your host's tooling; it is outside this repository.
