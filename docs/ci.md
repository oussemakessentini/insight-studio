# Continuous integration

`.github/workflows/ci.yml` runs on pushes to `main`, `integration/**` and `feature/**` and on pull requests.
It needs no secrets and pushes nothing (no images, no deployments). Read-only `contents` permission.

| Job | What | Time budget |
|---|---|---|
| Backend tests (a) | `scripts/ci/backend-tests.sh a`: accounts, businesses, billing, security, operations, mail, retention, tenancy, audit | 30 min |
| Backend tests (b) | `scripts/ci/backend-tests.sh b`: everything else except the Cube stack | 30 min |
| Cube checks | `node --test services/analytics/test/`, then `scripts/ci/backend-tests.sh cube` (Cube, Cube Store and PostgreSQL containers) | 45 min |
| Web checks | `npm ci`, lint, `tsc -b`, node tests, build | 15 min |
| Container images | build the three images, `scripts/ci/image-smoke.sh` (the API image starts in production mode, answers its probes on the management port only, logs JSON), Trivy scan (fixable HIGH/CRITICAL fail) | 30 min |
| Configuration checks | `docker compose config` of both compose files, `promtool check rules`, `shellcheck` | 10 min |
| Dependency review | `npm audit --omit=dev --audit-level=high`, OSV-Scanner on `package-lock.json` and `pom.xml`, GitHub dependency review on pull requests | 15 min |

## Splitting the backend suite

The whole suite (650+ tests, PostgreSQL in Testcontainers) takes longer than one comfortable job, so it is split
into shards. Shard **a** lists packages; shard **b** is "everything not in shard a and not a Cube test", so a new
test class always lands in exactly one shard. The Cube classes need their own containers and run in the Cube job.
Locally: `scripts/ci/backend-tests.sh all` (or `a`, `b`, `cube`).

## Flaky Cube tests

The Cube job reruns a failed test once (`-Dsurefire.rerunFailingTestsCount=1`). A test that failed and then passed
is listed under "flaky" in the job summary and the surefire reports are kept 14 days: intermittent Cube failures
are recorded, not hidden. They are an open release check ([release-checks.md](release-checks.md)).

## Verified locally

The workflow has not run on GitHub (nothing was pushed). Its parts were run locally: `actionlint` (no findings),
`shellcheck` on every script (no findings), both compose files (`docker compose config`), `promtool check rules`
(9 rules), the three image builds, the image smoke test (passed), the Trivy scans (API and web: 0 fixable
HIGH/CRITICAL), and the backend through `scripts/ci/backend-tests.sh`: shard a 201 tests and shard b 420 tests,
all passing; the whole suite (`all`, 637 tests) passed except one intermittent Cube test; the Cube shard passed
15/15 + 1/1 with one test recorded as flaky after its rerun.
