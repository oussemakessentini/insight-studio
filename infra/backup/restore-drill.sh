#!/usr/bin/env bash
# Restore drill (docs/backup-restore.md): restores a backup into a throwaway PostgreSQL container (never the
# live database), then verifies it against the backup's manifest: checksum of the file, Flyway version, exact
# row counts of every table, and the revenue checksum. Exits non-zero on any difference.
#
#   infra/backup/restore-drill.sh backups/insight-20261004T031700Z.dump      # or: the latest in BACKUP_DIR
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
# Newest first by modification time; the names are ours (insight-<timestamp>.dump).
# shellcheck disable=SC2012
dump="${1:-$(ls -1t "$BACKUP_DIR"/insight-*.dump 2>/dev/null | head -1)}"
[[ -n "$dump" && -f "$dump" ]] || { echo "No backup found" >&2; exit 2; }
manifest="${dump%.dump}.manifest"
[[ -f "$manifest" ]] || { echo "No manifest for $dump" >&2; exit 2; }
PG_IMAGE="${PG_IMAGE:-postgres:16@sha256:1a6ab3f5345eb6dbe04a1349529caabdb0ab09293a09590fad07b2246bfa4b54}"

echo "Restore drill of $dump"
(cd "$(dirname "$dump")" && sha256sum -c "$(basename "$dump").sha256")

container="insight-restore-drill-$$"
password="$(openssl rand -hex 16)"
trap 'docker rm -f "$container" > /dev/null 2>&1 || true' EXIT
docker run -d --name "$container" -e POSTGRES_DB=restored -e POSTGRES_USER=drill -e POSTGRES_PASSWORD="$password" \
  "$PG_IMAGE" > /dev/null
# The image first runs a temporary server to initialise the database (pg_isready already answers then),
# shuts it down and starts the real one: wait for the end of initialisation, then for the server.
for _ in $(seq 1 120); do
  docker logs "$container" 2>&1 | grep -q "PostgreSQL init process complete" && break
  sleep 1
done
for _ in $(seq 1 60); do docker exec "$container" pg_isready -U drill -d restored > /dev/null 2>&1 && break; sleep 1; done

started=$(date +%s)
docker exec -i "$container" pg_restore -U drill -d restored --no-owner --exit-on-error < "$dump"
echo "Restored in $(( $(date +%s) - started )) s"

# No -i: a query never reads stdin (inside the loop below, stdin is the manifest).
q() { docker exec "$container" psql -U drill -d restored -X -q -t -A -v ON_ERROR_STOP=1 -c "$1" < /dev/null; }
failures=0
expect() {
  local what="$1" want="$2" got="$3"
  if [[ "$want" == "$got" ]]; then
    echo "  ok    ${what} = ${got}"
  else
    echo "  FAIL  ${what}: backup had ${want}, restore has ${got}"
    failures=$((failures + 1))
  fi
}

expect flyway_version "$(grep '^flyway_version=' "$manifest" | cut -d= -f2)" \
  "$(q "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
expect revenue_checksum "$(grep '^revenue_checksum=' "$manifest" | cut -d= -f2)" \
  "$(q "SELECT COALESCE(sum(quantity * unit_price), 0) FROM sale_items")"
tables=0
while IFS='=' read -r key want; do
  table="${key#rows.}"
  expect "rows of ${table}" "$want" "$(q "SELECT count(*) FROM public.\"${table}\"")"
  tables=$((tables + 1))
done < <(grep '^rows\.' "$manifest")

expected_tables=$(grep -c '^rows\.' "$manifest")
if [[ "$tables" -ne "$expected_tables" || "$tables" -eq 0 ]]; then
  echo "  FAIL  compared ${tables} of the ${expected_tables} tables in the manifest"
  failures=$((failures + 1))
fi
if [[ "$failures" -gt 0 ]]; then
  echo "RESTORE DRILL FAILED: ${failures} difference(s)"
  exit 1
fi
echo "RESTORE DRILL PASSED: ${tables} tables, Flyway version and revenue match the backup"
