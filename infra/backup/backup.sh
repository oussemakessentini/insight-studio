#!/usr/bin/env bash
# Backs up the Insight Studio database (docs/backup-restore.md): a pg_dump custom-format archive, its SHA-256,
# and a manifest of what it holds (Flyway version, exact row counts, a revenue checksum) that the restore
# drill compares against. Keeps BACKUP_KEEP_DAYS days of backups.
#
#   DB_CONTAINER=insight-studio-db-1 POSTGRES_DB=insight_studio POSTGRES_USER=insight \
#   BACKUP_DIR=/var/backups/insight infra/backup/backup.sh
#
# Schedule it daily (cron or a systemd timer) and copy BACKUP_DIR off the host (object storage, another
# machine): a backup on the same disk as the database is not a backup.
set -euo pipefail

: "${DB_CONTAINER:?set DB_CONTAINER (the PostgreSQL container)}"
: "${POSTGRES_DB:?set POSTGRES_DB}"
: "${POSTGRES_USER:?set POSTGRES_USER}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
BACKUP_KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"

mkdir -p "$BACKUP_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
dump="$BACKUP_DIR/insight-${stamp}.dump"
manifest="$BACKUP_DIR/insight-${stamp}.manifest"

# The dump and the manifest describe exactly the same data: one REPEATABLE READ transaction exports its
# snapshot, pg_dump dumps that snapshot (--snapshot), and the manifest is read inside the same transaction,
# however busy the database is meanwhile.
coproc SNAPSHOT { docker exec -i "$DB_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X -q -t -A -v ON_ERROR_STOP=1; }
in="${SNAPSHOT[1]}"
out="${SNAPSHOT[0]}"
# Runs one query in the snapshot's session; prints its rows.
in_snapshot() {
  printf '%s;\nSELECT %s;\n' "$1" "'--end--'" >&"$in"
  local line
  while IFS= read -r line <&"$out"; do
    [[ "$line" == "--end--" ]] && break
    printf '%s\n' "$line"
  done
}
in_snapshot "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY" > /dev/null
snapshot="$(in_snapshot "SELECT pg_export_snapshot()")"
[[ -n "$snapshot" ]] || { echo "FAIL: could not export a snapshot" >&2; exit 1; }

docker exec "$DB_CONTAINER" pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --snapshot="$snapshot" \
  --format=custom --compress=6 --no-owner > "$dump"

{
  echo "created_at=${stamp}"
  echo "database=${POSTGRES_DB}"
  echo "flyway_version=$(in_snapshot "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
  echo "revenue_checksum=$(in_snapshot "SELECT COALESCE(sum(quantity * unit_price), 0) FROM sale_items")"
  in_snapshot "SELECT format('rows.%s=%s', c.relname,
                (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM public.%I', c.relname), false, true, '')))[1]::text)
              FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname = 'public' AND c.relkind = 'r' ORDER BY c.relname"
} > "$manifest"
in_snapshot "COMMIT" > /dev/null
exec {in}>&-
wait "$SNAPSHOT_PID" 2> /dev/null || true

(cd "$BACKUP_DIR" && sha256sum "$(basename "$dump")" > "$(basename "$dump").sha256")
size=$(wc -c < "$dump")
if [[ "$size" -lt 1024 ]]; then
  echo "FAIL: the dump is suspiciously small (${size} bytes)" >&2
  exit 1
fi
find "$BACKUP_DIR" -name 'insight-*' -type f -mtime "+${BACKUP_KEEP_DAYS}" -delete
echo "Backup ${dump} (${size} bytes), manifest ${manifest}"
