# Deployment and rollback

How to build, deploy, upgrade and roll back Insight Studio on a Docker host you control. Nothing in this
repository provisions cloud resources or deploys by itself. Before a release: [release-checks.md](release-checks.md).
Running it: [operations.md](operations.md). Backups: [backup-restore.md](backup-restore.md). The `prod`
profile's HTTPS, proxy and email rules: [production.md](production.md).

## What runs

`infra/compose.prod.yaml`:

| Service | Image | Published | Notes |
|---|---|---|---|
| `web` | `apps/web/Dockerfile` (nginx, unprivileged) | `WEB_PUBLISH` (e.g. `127.0.0.1:8090`) | serves the app, proxies `/api` to the API, never `/actuator`; security headers and CSP |
| `api` | `apps/api/Dockerfile` (JRE 21, uid 10001, read-only root) | no | `prod` profile; 8080 for the web proxy, 8081 management (probes, metrics) inside the network only |
| `db` | `postgres:16` (pinned) | no | volume `pgdata` |
| `cube`, `cubestore` | `services/analytics/Dockerfile`, `cubejs/cubestore` (pinned) | no | profile `analytics`; only with `REPORTS_ENGINE=cube` (not recommended yet) |
| `prometheus` | `prom/prometheus` (pinned) | `127.0.0.1:9091` | profile `monitoring`; scrapes the API, evaluates `infra/monitoring/alerts.yml` |

Put a TLS-terminating reverse proxy or load balancer in front of `WEB_PUBLISH` (the app must be served over
HTTPS; see production.md). Every base image is pinned by digest; update digests deliberately (and rebuild).

## Build

CI builds every image on each push (job "Container images") but pushes nothing. To build release images,
from the repository root at the release commit:

```bash
tag=$(git rev-parse --short=12 HEAD)
docker build -t insight-api:$tag apps/api
docker build -t insight-web:$tag apps/web
docker build -t insight-cube:$tag services/analytics      # only if you run the analytics profile
bash scripts/ci/image-smoke.sh "$tag"                       # the API image starts, answers its probes, logs JSON
```

Push them to your registry (`docker tag` + `docker push`) or copy them to the host
(`docker save insight-api:$tag | ssh host docker load`).

**Reproducible:** pinned base images, `npm ci` from the lockfile, and a fixed `project.build.outputTimestamp`
in `apps/api/pom.xml` (bump it when cutting a release). Two clean builds of the same commit produced
byte-identical API jars (SHA-256 `e6c20662…`) and web bundles (`82b772e8…`) during verification.

## First deployment

```bash
cp infra/.env.prod.example infra/.env.prod && chmod 600 infra/.env.prod   # fill in real values
# API_IMAGE / WEB_IMAGE = the tags you built
docker compose -f infra/compose.prod.yaml --env-file infra/.env.prod up -d
docker compose -f infra/compose.prod.yaml --env-file infra/.env.prod ps    # api and db "healthy"
```

The API applies database migrations (Flyway) on startup; several instances may start together (Flyway takes
a lock). Check from the host:

```bash
curl -fsS http://127.0.0.1:8090/api/session                                         # through the web proxy
docker compose -f infra/compose.prod.yaml --env-file infra/.env.prod exec api \
  curl -fsS http://127.0.0.1:8081/actuator/health/readiness                         # management port
```

Then schedule backups (backup-restore.md) and, with `--profile monitoring`, connect an Alertmanager.

Keep `REPORTS_ENGINE=sql` and `BILLING_PROVIDER=none` until their release checks pass.

## Upgrade

1. Read the release's migrations (`apps/api/src/main/resources/db/migration`, new `V*` files).
2. **Back up and run the restore drill** (backup-restore.md). Keep that backup until the release is accepted.
3. Set the new `API_IMAGE` / `WEB_IMAGE` tags in `infra/.env.prod` (keep a note of the previous tags).
4. `docker compose -f infra/compose.prod.yaml --env-file infra/.env.prod up -d` — compose replaces the API
   (migrations run on start), then the web container once the API is healthy.
5. Watch `/actuator/health/readiness`, the error-rate and job alerts, and the logs for 15 minutes.

Downtime: one API instance means a short gap while it restarts (seconds to a minute with migrations). For
no downtime, run two API instances behind the web proxy (sessions, rate limits, chart run limits and all
background work are shared through PostgreSQL) and replace them one at a time; migrations must then be
backward compatible with the previous version (the rule below).

## Rollback

Migrations are forward-only (Flyway). The policy that makes rollbacks cheap: **a release's migrations only
add** (new tables, new nullable columns, new indexes, relaxed constraints), so the previous image still runs
on the migrated schema. Destructive changes (drops, renames, tightened constraints) ship in a later release,
once nothing needs the old shape.

- **Application rollback** (the usual case): set `API_IMAGE` / `WEB_IMAGE` back to the previous tags and
  `docker compose ... up -d`. The previous version ignores the added tables and columns, and Flyway ignores
  applied migrations newer than it knows (its default `ignoreMigrationPatterns` is `*:future`). Verify
  readiness. (Checked for this release: the previous release's API started on a database migrated to V20.)
- **Data rollback** (a migration damaged data, or a destructive migration must be undone): stop the API, restore
  the pre-upgrade backup (backup-restore.md, "Restore"), start the previous images. Data written since the
  backup is lost: announce it, and prefer fixing forward when possible.

Rolling back while billing is enabled: subscriptions and webhooks keep flowing; events received while the
API was down are delivered again by the provider (it retries) and recorded once.

## Configuration reference

`infra/.env.prod.example` lists every setting with comments; the full list of `insight.*` settings is in
`apps/api/src/main/resources/application*.properties`, and the operations settings in operations.md.
Secrets live only in `infra/.env.prod` on the host (or your secret store); never in git, images or logs.
