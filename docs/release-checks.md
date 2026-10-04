# Release checks

What must be true before a release, and what is **not yet** resolved. A release goes out only when every
blocking check passes or is explicitly waived by the person releasing, with the reason written in the
release notes. Deployment steps: [deployment.md](deployment.md).

## Every release

| Check | How | Blocking |
|---|---|---|
| CI green on the release commit | `.github/workflows/ci.yml`: backend shards, Cube checks, web, images (smoke test, vulnerability scan), configuration, dependencies | yes |
| No flaky Cube test hidden by the rerun | the "Record flaky Cube tests" step summary of the Cube job is empty, or each entry is known (below) | yes, if `REPORTS_ENGINE=cube` |
| Database backup taken and the restore drill passed on it | [backup-restore.md](backup-restore.md) | yes |
| Migrations reviewed for rollback | new `V*__*.sql` files only add (tables, nullable columns, indexes); anything destructive needs the restore plan of [deployment.md](deployment.md) | yes |
| Load test baseline not regressed | [load-testing.md](load-testing.md): run against the release images, compare with the last recorded run | no (advisory) |
| `SQL` stays the report engine | `REPORTS_ENGINE` unset or `sql` in the environment file | yes |

## Unresolved (open)

### 1. Intermittent Cube failures — open

**What:** `CubeReportsIntegrationTest` (15 tests) has failed intermittently: 1 of 15 once (backend agent's run,
billing phase) and 3 of 15 once (account-management phase), each time with Cube itself answering
`QueryError: Internal: second time provided was later than self` or I/O errors (the API then answers 503
"temporarily unavailable"), followed by a cascade failure in a dependent test. Reruns on an idle machine
passed 15/15 every time; the failing runs overlapped other heavy Docker workloads on the same host.

**Why it matters:** with `REPORTS_ENGINE=cube`, users would see "temporarily unavailable" more often than
the design intends. The API never shows stale or wrong figures (it answers 503), so it is availability,
not correctness.

**Status:** not diagnosed. Cube is **not** the default (`REPORTS_ENGINE=sql`), and this blocks switching to
it. CI reruns a failed Cube test once and lists it as flaky in the job summary, so occurrences are recorded
instead of hidden; collect them before deciding.

**To close:** run the Cube trial ([cube-trial.md](cube-trial.md)) on production-like data and hardware; open
an issue upstream (Cube 1.7.46) with the error if it reproduces; decide on the engine only after the trial
passes and the flaky list stays empty for a few weeks of CI.

### 2. Real Stripe sandbox — open

**What:** the billing flows (checkout, portal, webhooks, cancellation on deletion, provider calls outside
transactions with idempotency keys) were tested with the **local fake provider** (same signature scheme,
same pipeline) and the **real Stripe adapter against a local stub** of Stripe's API that reproduces its
documented behaviour (idempotency keys, 409 for a key in flight, timeouts, errors). **No flow has run
against Stripe's own test-mode sandbox**: no test keys were available.

**Why it matters:** the stub can only reproduce what the documentation says. Field names, error codes, the
portal configuration and the exact event sequences of a real sandbox are unverified.

**Status:** `BILLING_PROVIDER=none` in production until this passes (the default in the `prod` profile and in
`infra/.env.prod.example`).

**To close:** follow "Stripe test-mode setup" in [billing-api.md](billing-api.md) with a test-mode account
(`sk_test_…`, a test price, `stripe listen` forwarding webhooks), then run, with Stripe's test cards: checkout
paid (4242…) and declined (4000 0000 0000 0002), 3-D Secure (4000 0027 6000 3184), portal cancel at period
end and immediately, a failed renewal (test clock), a webhook replay (`stripe events resend`), and a business
deletion with a live subscription and with an open checkout. Record the results here.

### 3. Webhook ingestion latency under combined load — open (advisory)

The 2-minute load test ([load-testing.md](load-testing.md)) met every threshold except webhook ingestion:
p95 507 ms against a 500 ms target, while 12 users loaded dashboards of the same business. Two runs on the
same laptop differed widely (an earlier run missed three latency targets), so this is a baseline on shared
hardware, not a capacity figure. Re-measure on production-like hardware before relying on any number.
