#!/usr/bin/env bash
# Runs the load test (docs/load-testing.md) on a throwaway stack and removes it afterwards.
#
#   API_IMAGE=insight-api:<tag> WEB_IMAGE=insight-web:<tag> tests/load/run.sh            # 2 minutes
#   LOAD_DURATION=10m API_IMAGE=... WEB_IMAGE=... tests/load/run.sh
#
# Results: tests/load/results/summary.json (k6) and results/api-stats.txt (the API container's CPU/memory).
set -euo pipefail

cd "$(dirname "$0")"
: "${API_IMAGE:?set API_IMAGE}"
: "${WEB_IMAGE:?set WEB_IMAGE}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:1.3.0}"
OWNERS="${OWNERS:-4}"
LOAD_DB_PASSWORD="$(openssl rand -hex 16)"
export API_IMAGE WEB_IMAGE LOAD_DB_PASSWORD
compose() { docker compose -f compose.load.yaml "$@"; }
# Called by the EXIT trap below.
# shellcheck disable=SC2329
cleanup() { compose down -v --remove-orphans > /dev/null 2>&1 || true; }
trap cleanup EXIT
mkdir -p results

compose up -d --quiet-pull
echo "Waiting for the API (migrations and the demo data set)..."
for _ in $(seq 1 120); do
  status=$(docker inspect -f '{{.State.Health.Status}}' "$(compose ps -q api)" 2>/dev/null || echo starting)
  [[ "$status" == "healthy" ]] && break
  sleep 3
done
[[ "$status" == "healthy" ]] || { compose logs api | tail -40; echo "API not healthy" >&2; exit 1; }
for _ in $(seq 1 60); do
  curl -fsS -o /dev/null http://127.0.0.1:8097/api/session && break
  sleep 2
done

sql() { docker exec -i "$(compose ps -q db)" psql -U insight -d insight -X -q -t -A -v ON_ERROR_STOP=1 -c "$1"; }
until [[ "$(sql "SELECT count(*) FROM businesses WHERE slug = 'fieldstone-apparel'")" == "1" ]]; do sleep 2; done
business=$(sql "SELECT id FROM businesses WHERE slug = 'fieldstone-apparel'")

# Owners of the demo business (sign-up through the API, then verified and made owners directly).
jar=$(mktemp)
for i in $(seq 1 "$OWNERS"); do
  curl -s -c "$jar" -b "$jar" -o /dev/null http://127.0.0.1:8097/api/session
  xsrf=$(awk '$6 == "XSRF-TOKEN" {print $7}' "$jar")
  curl -s -c "$jar" -b "$jar" -o /dev/null -H "X-XSRF-TOKEN: ${xsrf}" -H 'Content-Type: application/json' \
    -d "{\"email\":\"load${i}@load.test\",\"password\":\"correct horse battery staple\",\"displayName\":\"Load ${i}\"}" \
    http://127.0.0.1:8097/api/auth/sign-up
done
rm -f "$jar"
sql "UPDATE users SET email_verified_at = now() WHERE email LIKE '%@load.test'"
sql "INSERT INTO memberships (user_id, business_id, role) SELECT id, ${business}, 'OWNER' FROM users WHERE email LIKE 'load%@load.test'"

network="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$(compose ps -q api)")"
sales_before=$(sql "SELECT count(*) FROM sales")
( while sleep 5; do docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' "$(compose ps -q api)" "$(compose ps -q db)"; done ) \
  > results/api-stats.txt 2>/dev/null &
stats_pid=$!

set +e
# MSYS_NO_PATHCONV: keeps Git Bash on Windows from rewriting the container paths (ignored elsewhere).
MSYS_NO_PATHCONV=1 docker run --rm --network "$network" -v "$(pwd -W 2>/dev/null || pwd):/load" "$K6_IMAGE" run \
  -e WEB=http://web:8080 -e MGMT=http://api:8081 -e BUSINESS_ID="$business" -e OWNERS="$OWNERS" \
  -e LOAD_DURATION="${LOAD_DURATION:-2m}" --summary-export /load/results/summary.json /load/load.js
result=$?
set -e
kill "$stats_pid" 2> /dev/null || true

echo "Sales imported during the run: $(( $(sql "SELECT count(*) FROM sales") - sales_before ))"
echo "Webhook events: $(sql "SELECT status || '=' || count(*) FROM billing_events GROUP BY status" | tr '\n' ' ')"
echo "Emails: $(sql "SELECT status || '=' || count(*) FROM mail_outbox GROUP BY status" | tr '\n' ' ')"
echo "Chart run slots left: $(sql "SELECT count(*) FROM chart_run_slots")"
echo "Peak API/DB usage: $(sort -t= -k2 -n results/api-stats.txt | tail -2 | tr '\n' ' ')"
exit "$result"
