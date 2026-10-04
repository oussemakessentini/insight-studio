# Load testing

`tests/load/` drives the production images with [k6](https://grafana.com/docs/k6/) on a throwaway stack
(`tests/load/compose.load.yaml`: PostgreSQL, Mailpit, the API with the demo data set of 10 397 sales and the
local fake payment provider, the web container). Nothing external is called and the stack is removed at the
end. Never point it at a real deployment.

```bash
docker build -t insight-api:load apps/api && docker build -t insight-web:load apps/web
API_IMAGE=insight-api:load WEB_IMAGE=insight-web:load tests/load/run.sh          # 2 minutes
LOAD_DURATION=10m API_IMAGE=... WEB_IMAGE=... tests/load/run.sh
```

Results: `tests/load/results/summary.json` (k6) and `results/api-stats.txt` (container CPU and memory every 5 s).

## Scenarios (all at once, one business)

| Scenario | Load | What it exercises |
|---|---|---|
| `dashboards` | 12 users, each loading a full dashboard (4 overview queries + 6 saved charts, 3 at a time like the web app) about every second | report SQL, chart runs, the shared chart run limit (429s are counted, not failures) |
| `imports` | one 200-line sales CSV import every 2 s (new receipts each time) | CSV parsing, validation, inserts, plan limits, audit |
| `exports` | a monthly CSV, monthly PDF or categories PDF every 3 s | report rendering and streaming |
| `business_exports` | 4 full business ZIP exports (the rate limit allows 5 per hour) | the ZIP export over the whole data set |
| `webhooks` | 10 signed payment webhooks per second | signature check, recording, the event worker |
| `invitations` | 60 invitations (20 per account per hour is the rate limit) | the mail outbox and SMTP worker |

Thresholds (the run fails when one is missed): no scenario above 1 % failed requests; p95 overview < 1.5 s,
chart run < 2 s, import < 5 s, export < 5 s, business ZIP < 20 s, webhook < 500 ms; and the background queues
(webhook events, emails) drain within 120 s after the load stops.

## Baseline (2026-10-04, one Windows laptop, Docker Desktop; API limited to 2 CPUs and 1.5 GB)

| Measure | Result |
|---|---|
| Requests | 6 472 in 2 min (47/s), **0 % failed** in every scenario |
| Overview queries | p50 685 ms, p95 1.30 s |
| Chart runs | p50 243 ms, p95 1.04 s; **2 140 answered 429** by the per-business run limit (6) |
| Sales imports (200 lines) | p50 427 ms, p95 1.62 s; 12 200 sales imported |
| Report CSV/PDF exports | p50 642 ms, p95 2.42 s |
| Business ZIP exports | p95 1.24 s |
| Webhooks | p50 71 ms, **p95 507 ms (threshold 500 ms: missed)** |
| Background queues | 1 200 webhook events and 64 emails processed; drained 16 s after the load stopped |
| Resources (sampled every 5 s) | API peak 418 MB and 1.2 CPUs; PostgreSQL peak 7.2 CPUs |

Findings:

- **PostgreSQL CPU is the bottleneck.** Twelve people hammering one business's dashboards keep it busy; the chart
  run limit sheds the excess (2 140 refused runs, retried by the web app). Raise
  `CHARTS_MAX_CONCURRENT_RUNS_PER_BUSINESS` only with more database capacity.
- **Webhook ingestion p95 missed its target by 7 ms** while dashboards saturated the database: recorded as an
  open release check ([release-checks.md](release-checks.md)).
- **Noisy hardware.** An earlier run of the same profile on the same machine missed three latency targets
  (charts p95 2.68 s, overview 1.9 s, webhooks 988 ms). Measure on production-like hardware before relying on
  any figure, and compare runs on the same machine only.

Problems the load test found and fixed while it was written (test bugs, not API bugs): signing several
accounts in on one session (the API's session-fixation protection correctly moved the session to the last
account), an invalid chart definition (average order value grouped by product, refused by the API), and too few
pre-allocated virtual users for the webhook rate.
