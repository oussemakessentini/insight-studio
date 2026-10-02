# Cube report trial

How to decide, with measurements, whether the Cube report engine (`REPORTS_ENGINE=cube`,
[cube-reports.md](cube-reports.md)) can replace the SQL engine. The default stays
`REPORTS_ENGINE=sql` until a trial on production-like data passes and the decision is recorded.

The trial is scripted (`services/analytics/scripts/cube-trial.mjs`, Node 20+, no dependencies), so
anyone can repeat it and compare runs. It never changes the default and touches only its own
businesses.

## What it checks

| Scenario | What happens | Pass criteria (defaults) |
|---|---|---|
| **Correctness** | Every trial business × 6 windows (all data, a quarter, both DST changes, a month end, a period without sales) × every store and all stores × monthly and categories, as JSON and CSV, through both engines | Byte-identical answers, Cube engine header present |
| **Concurrent businesses** | `TRIAL_CONCURRENCY` workers per business (3 businesses in 3 time zones) request random reports for `TRIAL_SECONDS`, first against the SQL engine (baseline, half as long), then against Cube | No errors other than `503` problem details; Cube p95 ≤ 2 s; `503` ≤ 5 % of Cube requests |
| **Imports during reporting** | During the Cube run, every `TRIAL_IMPORT_EVERY_SECONDS` a CSV import adds two receipts (one at 23:30 local time) to a business, then the importer polls that day's report | Never a `200` that differs from SQL (no stale figures); every import visible within 60 s (median reported) |
| **Cold start** | `docker restart` of the Cube container; every business polled until it answers | Each answers within 180 s; before that only `503` with `Retry-After`; then the same figures as SQL |
| **503 recovery** | `docker stop` of the Cube container: JSON, CSV and PDF requested; then `docker start` | `503` problem details with `Retry-After` for all three (never a file, never figures); recovery within 180 s with the same figures as SQL |

Thresholds are environment variables (`LIMIT_CUBE_P95_MS`, `LIMIT_503_SHARE`, `LIMIT_FRESH_MS`,
`LIMIT_COLD_START_MS`, `LIMIT_RECOVERY_MS`): agree on them before the trial, not after.

Response times are measured by the script around each HTTP request (API + Cube + network) on the
machine running it; run it next to the API, not over a slow link. The SQL baseline runs first with
the same mix, so the two engines are compared on the same data and machine.

## Set up (once)

Use a database dedicated to the trial (a restored copy of production data is best; the demo data
works for a dry run). The trial creates three businesses ("Trial New York", "Trial Paris",
"Trial Auckland") and imports about 1,500 receipts into each, plus a few receipts per import during
the run. There is no way to delete a business from the app, which is another reason to use a
separate database.

1. **Database, mail and Cube** (Windows Command Prompt, from the repository root). In `infra\.env`
   set `CUBEJS_API_SECRET` and list every time zone the trial uses (plus your businesses' zones):

   ```bat
   rem infra\.env
   CUBEJS_SCHEDULED_REFRESH_TIMEZONES=America/New_York,Europe/Paris,Pacific/Auckland
   ```

   ```bat
   cd infra
   docker compose --profile analytics up -d
   docker compose ps
   ```

   Note the Cube container's name (`infra-cube-1` with the default project name); the script
   restarts and stops it.

2. **Two API instances on the same database**, one per engine (they share sessions, so one sign-in
   works on both). Build once, then start each in its own window; ports 8081 and 8082 keep 8080 free
   for development:

   ```bat
   cd apps\api
   .\mvnw.cmd -q -DskipTests package

   set REPORTS_ENGINE=sql
   java -jar target\insight-api-0.0.1-SNAPSHOT.jar --server.port=8081

   rem second window
   set REPORTS_ENGINE=cube
   set INSIGHT_CUBE_URL=http://localhost:4000
   java -jar target\insight-api-0.0.1-SNAPSHOT.jar --server.port=8082
   ```

   The Cube instance logs "Reports are computed by the cube engine (deadline PT10S)".

3. **A trial account.** Sign up through the web app pointed at one of the APIs, open the
   verification link from Mailpit (http://localhost:8025), and keep the email and password for the
   script. The account becomes the owner of the trial businesses.

   ```bat
   cd apps\web
   set API_PROXY_TARGET=http://localhost:8082
   npm run dev -- --port 5174
   ```

   Then open http://localhost:5174/sign-up. Stop the dev server before the run (it is not needed).

4. **Trial data:**

   ```bat
   set SQL_API=http://localhost:8081
   set CUBE_API=http://localhost:8082
   set TRIAL_EMAIL=trial@example.com
   set TRIAL_PASSWORD=...
   node services\analytics\scripts\cube-trial.mjs prepare
   ```

   `prepare` is idempotent: businesses that already have data are left as they are. The generated
   history is deterministic (fixed seed), spans January–September 2026, includes price changes,
   23:30 local-time receipts and multi-line receipts in three stores per business.

## Run

```bat
set TRIAL_SECONDS=120
set TRIAL_CONCURRENCY=4
set CUBE_CONTAINER=infra-cube-1
node services\analytics\scripts\cube-trial.mjs run
```

The script prints each criterion as it is checked and writes `cube-trial-results\cube-trial-<time>.json`
(every measurement) and `.md` (the summary table below). Exit code 0 means every criterion passed.
`SKIP_DOCKER=1` skips the cold-start and outage scenarios (e.g. when Cube runs elsewhere).

Run it at least three times, and once with `TRIAL_CONCURRENCY` raised to the expected peak (for
example 12). Keep every result file with the decision.

## Record the decision

| Item | Value |
|---|---|
| Date, commit (`git rev-parse --short HEAD`) | |
| Data (copy of production from …, or demo) and size (businesses, receipts) | |
| Machine (CPU, RAM; Docker Desktop or Linux) | |
| Runs and their result files | |
| SQL p50 / p95, Cube p50 / p95 (worst run) | |
| 503 share, import freshness median / max, cold start, recovery (worst run) | |
| Failed criteria and their explanation | |
| Decision: keep `sql` / switch to `cube` (who, when) | |

Switching is `REPORTS_ENGINE=cube` on the API plus a restart; going back is `REPORTS_ENGINE=sql` and
a restart. Nothing else changes (same database, same responses), so the switch can be reversed at
any time.

## Dry run (2026-10-02, for reference)

A first run of the script on a development machine (Windows, Docker Desktop, everything on one
machine) with the demo business plus the three trial businesses, `TRIAL_SECONDS=60`,
`TRIAL_CONCURRENCY=4`. It validates the procedure; it is **not** the decision run, which needs
production-like data and hardware.

| Criterion | Result |
|---|---|
| Correctness | 288 JSON/CSV comparisons, 0 mismatches |
| Errors other than 503 | 0 (Cube and SQL) |
| 503 share (Cube) | 0.7 % (9 of 1,218) |
| Stale answers after imports | 0 of 4 imports; visible after 3.1 s median, 4.8 s max |
| Cold start | businesses answering after 6, 14 and 23 s |
| Cube stopped | JSON, CSV and PDF all `503`, `Retry-After: 60` |
| Recovery after restart | 16, 61 and 64 s |

| Engine | Requests | Per second | p50 | p95 | p99 |
|---|---|---|---|---|---|
| SQL | 3,985 | 132.8 | 65 ms | 185 ms | 453 ms |
| Cube | 1,218 | 20.3 | 251 ms | 1,230 ms | 6,489 ms |

All ten criteria passed, but Cube was about six times slower than SQL at this data size, and its
p99 came within a few seconds of the 10-second deadline (`REPORTS_CUBE_TIMEOUT`). The decision run
should look closely at p99 and the 503 share at the expected peak concurrency.

## Interpreting results

- **Mismatches** (correctness, or a stale answer after an import) are blockers: the engines must
  agree exactly. Keep the JSON file and the API log; reproduce with the printed path.
- **Many `503` "temporarily unavailable" (Retry-After 30)** under load mean Cube cannot answer within
  `REPORTS_CUBE_TIMEOUT`: check that every business time zone is in
  `CUBEJS_SCHEDULED_REFRESH_TIMEZONES` (a missing zone is built on demand), then consider more Cube
  resources or a longer timeout.
- **`503` "being updated" (Retry-After 5)** right after imports are expected while Cube rebuilds;
  the freshness median shows how long users would wait.
- **Cold start** time is how long reports are unavailable after a Cube deployment or restart; plan
  deployments accordingly (or keep `sql` during maintenance).
