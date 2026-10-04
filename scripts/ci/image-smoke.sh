#!/usr/bin/env bash
# Starts the API image the way production does (prod profile, management port, structured logs) against a
# throwaway PostgreSQL, and checks its probes and log format. Usage: scripts/ci/image-smoke.sh <image tag>
set -euo pipefail

tag="${1:?usage: $0 <image tag>}"
net="insight-smoke-$$"
password="$(openssl rand -hex 16)"
cleanup() { docker rm -f "smoke-db-$$" "smoke-api-$$" > /dev/null 2>&1 || true; docker network rm "$net" > /dev/null 2>&1 || true; }
trap cleanup EXIT

docker network create "$net" > /dev/null
docker run -d --name "smoke-db-$$" --network "$net" -e POSTGRES_DB=insight -e POSTGRES_USER=insight \
  -e POSTGRES_PASSWORD="$password" postgres:16 > /dev/null
for _ in $(seq 1 30); do docker exec "smoke-db-$$" pg_isready -U insight -d insight > /dev/null 2>&1 && break; sleep 1; done

docker run -d --name "smoke-api-$$" --network "$net" \
  -e DB_URL="jdbc:postgresql://smoke-db-$$:5432/insight" -e DB_USERNAME=insight -e DB_PASSWORD="$password" \
  -e WEB_BASE_URL=https://insight.example.com -e MAIL_HOST=smtp.invalid -e MAIL_FROM="Insight <no-reply@example.com>" \
  "insight-api:${tag}" > /dev/null

ok=""
for _ in $(seq 1 90); do
  if docker exec "smoke-api-$$" curl -fsS http://127.0.0.1:8081/actuator/health/readiness > /dev/null 2>&1; then ok=1; break; fi
  sleep 2
done
if [[ -z "$ok" ]]; then
  docker logs "smoke-api-$$" | tail -50
  echo "FAIL: the API never became ready" >&2
  exit 1
fi
docker exec "smoke-api-$$" curl -fsS http://127.0.0.1:8081/actuator/health/liveness
echo
# Captured first: grep -q stops reading early, which pipefail would report as curl failing.
metrics=$(docker exec "smoke-api-$$" curl -fsS http://127.0.0.1:8081/actuator/prometheus)
grep -q '^insight_jobs_pending' <<< "$metrics" || { echo "FAIL: no job metrics" >&2; exit 1; }
# The public port must not serve the actuator in production.
# Only the status matters (curl may report a write error for the discarded body).
code=$(docker exec "smoke-api-$$" curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/prometheus || true)
[[ "$code" != "200" ]] || { echo "FAIL: metrics exposed on the public port" >&2; exit 1; }
# Every log line is one JSON object (ECS).
logs=$(docker logs "smoke-api-$$" 2>&1)
lines=$(grep -v '^Picked up JAVA_TOOL_OPTIONS' <<< "$logs" | sed -n '1,20p')
if grep -qv '^{' <<< "$lines"; then
  head -5 <<< "$lines"
  echo "FAIL: logs are not structured" >&2
  exit 1
fi
grep -q '"@timestamp"' <<< "$lines" || { echo "FAIL: not ECS" >&2; exit 1; }
echo "PASS: image ${tag} starts, answers its probes on 8081 only, and logs JSON"
