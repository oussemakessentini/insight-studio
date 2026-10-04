# Operations

Running Insight Studio in production: health, logs, metrics, alerts, background jobs and limits. Deploying:
[deployment.md](deployment.md). Backups: [backup-restore.md](backup-restore.md).

## Health

The actuator listens on the **management port** (`MANAGEMENT_PORT`, 8081 with the `prod` profile), which is
never published and never proxied by the web container (`/actuator` answers 404 there).

| Endpoint | Meaning | Use |
|---|---|---|
| `/actuator/health/liveness` | the process is alive | restart the container when it fails (the image's `HEALTHCHECK`) |
| `/actuator/health/readiness` | it can serve: the database answers | send traffic only when UP (load balancer, compose `depends_on`) |
| `/actuator/health/jobs` | background queues are moving | **alerting only**: DOWN never removes an instance, because the queues are shared and a restart would not help |
| `/actuator/health` | everything, with details | diagnosis |

`jobs` is DOWN when an item of any queue is overdue by more than `OPS_JOB_OVERDUE_ALERT` (15 minutes) or has
failed `OPS_JOB_ATTEMPTS_ALERT` (6) times; its details list each queue (`pending`, `overdue`, `maxAttempts`,
`status` OK/DELAYED/FAILING).

## Logs

With the `prod` profile every line is one JSON object (Elastic Common Schema; `LOG_FORMAT`, default `ecs`) on
stdout: `@timestamp`, `log.level`, `log.logger`, `message`, `service.name`, `error.*`, and **`requestId`** for
lines written while serving a request. The same id is returned as `X-Request-Id`; the web container sets it
from nginx's `$request_id`, so a request can be followed from the proxy to the API. A user reporting a
problem can give the id from the browser's network panel.

Logs never contain passwords, tokens, email links, email bodies, CSV contents or payment provider payloads
(enforced in code and tested: audit details, mail outbox, billing). Docker keeps 5 × 20 MB per container
(`logging` in compose); ship them to your log store from there.

## Metrics

`/actuator/prometheus` on the management port (the `monitoring` profile's Prometheus scrapes it). Besides
the standard JVM, HTTP (`http_server_requests_seconds`, with histograms), Tomcat and HikariCP metrics:

| Metric | Meaning |
|---|---|
| `insight_jobs_pending{queue}` | items waiting in each durable queue |
| `insight_jobs_overdue_seconds{queue}` | how long the most overdue item has waited past its due time |
| `insight_jobs_max_attempts{queue}` | most attempts made by a pending item |
| `insight_job_last_success_seconds{worker}` / `insight_job_last_failure_seconds{worker}` | Unix time of the worker's last successful / failed round on this instance |

Queues (`queue`): `mail_outbox`, `cube_purge`, `billing_events`, `billing_cancellations`, `checkout_expiries`.
Workers (`worker`): `mail_outbox`, `cube_purge`, `billing_events`, `billing_cancellations`, `retention`.
The queue values are read from PostgreSQL every 15 s and are the same on every instance.

## Alerts

`infra/monitoring/alerts.yml` (validated by `promtool` in CI). Route them with an Alertmanager of your choice.

| Alert | Fires when | Severity |
|---|---|---|
| `InsightApiDown` | an instance is not scraped for 2 min | critical |
| `InsightApiHighErrorRate` | over 5 % of requests are 5xx for 10 min | warning |
| `InsightApiSlowRequests` | p95 latency over 2 s for 15 min | warning |
| `InsightDatabasePoolExhausted` | requests wait for a database connection for 5 min | warning |
| `InsightJobQueueDelayed` | an item is over 15 min overdue | warning |
| `InsightJobFailing` | an item has failed 6 times | warning |
| `InsightBillingCancellationStuck` | a deleted business's subscription is not canceled after 1 h (it may still be charged) | critical |
| `InsightWorkerStalled` | no instance completed a worker round for 10 min | warning |
| `InsightRetentionNotRun` | the daily retention purge has not completed for 26 h | warning |

## Runbooks

### API down

`docker compose ... ps` and `logs api`. Most often: the database is unreachable (readiness DOWN), a failed
migration (the API refuses to start; the log names the migration), or memory (`-XX:+ExitOnOutOfMemoryError`
restarts it; raise `API_MEMORY`). Roll back the image if a release caused it ([deployment.md](deployment.md)).

### Errors

Find failing requests by status in the logs (`log.level` ERROR, `error.type`), follow one by `requestId`.
503s with "temporarily unavailable" come from Cube (only with `REPORTS_ENGINE=cube`) or the payment
provider; 429s are rate limits or the chart run limit (expected under load), not errors.

### Slow

Check `hikaricp_connections_pending` and database CPU. Dashboards are the heaviest traffic; the chart run
limit (`CHARTS_MAX_CONCURRENT_RUNS_PER_BUSINESS`, 6 per business **across all instances**) protects the
database from one business's dashboards; the web app retries its 429s. See the load test baseline
([load-testing.md](load-testing.md)).

### Background jobs

Every queue is a PostgreSQL table, processed by every instance with leases; items are retried with
backoff until they succeed. Look at the row:

```sql
SELECT id, attempts, next_attempt_at, last_error FROM billing_events WHERE status = 'PENDING' ORDER BY next_attempt_at LIMIT 20;
-- likewise mail_outbox, cube_purge_requests, billing_cancellations, billing_operations (kind = 'expire_checkout')
```

`last_error` says why (SMTP refused, Stripe unreachable, Cube down, …). Fix the cause; the next attempt picks it
up (set `next_attempt_at = now()` to retry at once). A stalled worker with an empty queue usually means every
instance is down or has the worker disabled (`insight.*.enabled=false`).

## Limits and settings

| Setting | Default | What |
|---|---|---|
| `CHARTS_MAX_CONCURRENT_RUNS_PER_BUSINESS` | 6 | chart runs in progress per business across all instances (`chart_run_slots`); 429 beyond |
| `insight.charts.run-slot-ttl` | 2 min | a crashed instance's run slot stops counting after this |
| `CHARTS_STATEMENT_TIMEOUT` | 10 s | longest chart query |
| `OPS_JOB_OVERDUE_ALERT`, `OPS_JOB_ATTEMPTS_ALERT` | 15 min, 6 | `jobs` health thresholds |
| `MANAGEMENT_PORT` | 8081 (`prod`) | actuator port |
| `LOG_FORMAT` | `ecs` (`prod`) | structured log format (`ecs`, `logstash`, `gelf`) |

Sessions, rate limits, chart run slots and every background queue live in PostgreSQL, so any number of API
instances can run behind the web proxy.
