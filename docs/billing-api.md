# Subscription billing: API

Backend implementation of [billing-contract.md](billing-contract.md). Billing is per business (Free or
Pro); accounts are never billed. Code: `apps/api/src/main/java/.../billing/`. Schema: Flyway
`V18__create_billing.sql`.

## Endpoints

| Method & path | Who | Answer |
|---|---|---|
| `GET /api/billing/plans` | signed in | `[Plan]`, Free first |
| `GET /api/businesses/{id}/billing` | ADMIN+ | `Billing` |
| `POST /api/businesses/{id}/billing/checkout` `{"plan":"pro"}` | OWNER, verified email | `{url}`; `400` unknown/free plan; `409` already on a paid plan (use the portal); `503` provider unavailable (`Retry-After`) |
| `POST /api/businesses/{id}/billing/portal` | OWNER, verified email | `{url}`; `409` no billing account yet; `503` provider unavailable |
| `POST /api/billing/webhooks/{provider}` | the provider (signed) | `200 {"received":true}`; `400` invalid; `404` not the configured provider |
| `GET /api/billing/fake/{sessionId}` | OWNER of the session's business | fake session (provider `fake` only) |
| `POST /api/billing/fake/{sessionId}/{action}` | OWNER of the session's business | fake session after the action |

- The business in the path is a selector: `404` when the caller is not a member, `403` for a weaker
  role. Signed-out callers get `401`; writes need the CSRF header (except webhooks).
- Billing off (`BILLING_PROVIDER=none`) and the configured public demo business (by slug, even if
  someone gave it members): every billing endpoint answers `404`; checkout is never possible.
- `Billing` = `{plan: Plan, subscription: {status, cancelAtPeriodEnd, currentPeriodEnd, canceledAt} | null,
  paymentProblem, provider: "fake"|"stripe"|"none", canManage, usage: [{resource, used, limit}], plans: [Plan]}`.
  `subscription` is `null` until the provider confirmed one. `paymentProblem` is true for `past_due`
  (still Pro: grace while the provider retries) and `unpaid` (Free). `usage` lists, in this order,
  `members` (memberships + open invitations), `stores`, `charts`, `dashboards`, `importsPerMonth`.
- `Plan` = `{key, name, priceDisplay, limits: {members, stores, charts, dashboards, importsPerMonth}}`.
- Provider ids (customer, subscription, price) and secrets are never returned.
- Checkout `success_url` / `cancel_url` and the portal `return_url` are built only from configuration:
  `WEB_BASE_URL` + `/settings/billing?checkout=success`, `?checkout=canceled`, and `/settings/billing`.

### Plan limit refusals

Every creation over a limit answers `409`:

```json
{"type": "about:blank", "title": "Conflict", "status": 409,
 "detail": "The Free plan allows 2 stores. Upgrade to Pro or delete one first.",
 "code": "plan_limit", "resource": "stores", "limit": 2, "used": 2, "plan": "free", "upgradeAvailable": true}
```

`resource` is one of `members`, `stores`, `charts`, `dashboards`, `importsPerMonth`; `plan` is the
plan key; `upgradeAvailable` is true when another plan has a larger limit for that resource (never with
billing off). Details by path:

| Path | Counts | Example detail |
|---|---|---|
| send an invitation | members + open invitations (re-inviting an address replaces its invitation first) | `The Free plan allows 3 members (open invitations count). Upgrade to Pro or remove a member or revoke an invitation first.` |
| accept an invitation | members only (the invitation's own seat is not counted twice) | `This business is full: the Free plan allows 3 members. Ask an owner to upgrade or remove a member first.` |
| `POST /api/stores` | stores | `The Free plan allows 2 stores. Upgrade to Pro or delete one first.` |
| stores import (real) | stores + stores the file would create | `This would add 3 stores; the Free plan allows 2 stores and the business has 1. Upgrade to Pro or delete some first.` (`used` = current stores) |
| charts create / duplicate | saved charts | `The Free plan allows 10 charts. …` |
| dashboards create / duplicate | dashboards | `The Free plan allows 3 dashboards. …` |
| any real import (sales, stores, products) | completed imports since the 1st of the month in the business's time zone | `The Free plan allows 10 imports per month. Upgrade to Pro or wait until next month.` |

A **dry run** (and the errors file) never answers `409` for a plan limit: it reports the store limit and
the monthly import limit as file errors (`line: null`) in a `REJECTED` result. Rejected imports and dry
runs do not count. The absolute caps (200 charts, 50 dashboards) are checked first and keep their own
messages.

Never limited: reading, running, exporting, editing, deleting, leaving, changing roles, the demo seeder
(it writes SQL directly). Nothing is deleted or hidden by a downgrade.

### Fake provider pages API

`GET /api/billing/fake/{sessionId}` →

```json
{"id": "cs_fake_…", "kind": "checkout", "businessId": 7, "businessName": "Corner Shop",
 "plan": {"key": "pro", "name": "Pro", "priceDisplay": "$29 / month", "limits": {…}},
 "status": "open", "declined": false,
 "subscription": {"status": "active", "cancelAtPeriodEnd": false, "currentPeriodEnd": "2026-11-02T…Z", "canceledAt": null},
 "successUrl": "…/settings/billing?checkout=success", "cancelUrl": "…/settings/billing?checkout=canceled",
 "returnUrl": null, "actions": ["pay", "decline", "cancel"], "redirectUrl": null}
```

- `kind`: `checkout` (`cs_fake_…`) or `portal` (`bps_fake_…`). `status`: checkout `open`, `declined` (the
  last attempt was declined; pay can be tried again) or `complete`; portal `open`. `subscription` is
  `null` before any payment attempt.
- `POST …/{action}` answers the same body; `redirectUrl` says where to go next (`pay`: the success URL;
  `cancel`: the cancel URL; otherwise `null`). Unknown action `404`; an action on an ended subscription
  or a completed checkout `409`.
- Checkout actions: `pay` (subscription `active`), `decline` (subscription `incomplete`: still Free),
  `cancel`. Portal actions: `cancel-at-period-end`, `resume`, `cancel-now`, `fail-renewal` (`past_due`),
  `unpaid`, `pay-outstanding` (`active`, new period), and an extra `end-period` (the current period ends
  now: canceled if set to cancel at period end, renewed otherwise).
- Checkout and portal URLs returned by the API are `WEB_BASE_URL` + `/billing/fake/checkout/{sessionId}`
  and `/billing/fake/portal/{sessionId}`.

## Plans configuration

Plans live in `insight.billing.plans.<key>.*` (application.properties), with environment overrides:

| Property | Env variable | Default |
|---|---|---|
| `insight.billing.plans.free.name` / `.price-display` | `BILLING_FREE_NAME`, `BILLING_FREE_PRICE_DISPLAY` | `Free`, `Free` |
| `insight.billing.plans.free.limits.members` | `BILLING_FREE_MAX_MEMBERS` | 3 |
| `….free.limits.stores` | `BILLING_FREE_MAX_STORES` | 2 |
| `….free.limits.charts` | `BILLING_FREE_MAX_CHARTS` | 10 |
| `….free.limits.dashboards` | `BILLING_FREE_MAX_DASHBOARDS` | 3 |
| `….free.limits.imports-per-month` | `BILLING_FREE_MAX_IMPORTS_PER_MONTH` | 10 |
| `insight.billing.plans.pro.name` / `.price-display` | `BILLING_PRO_NAME`, `BILLING_PRO_PRICE_DISPLAY` | `Pro`, `$29 / month` |
| `insight.billing.plans.pro.provider-price-id` | `BILLING_PRO_PRICE_ID` | empty (required with Stripe) |
| `….pro.limits.members` / `stores` / `charts` / `dashboards` / `imports-per-month` | `BILLING_PRO_MAX_MEMBERS`, `BILLING_PRO_MAX_STORES`, `BILLING_PRO_MAX_CHARTS`, `BILLING_PRO_MAX_DASHBOARDS`, `BILLING_PRO_MAX_IMPORTS_PER_MONTH` | 25, 50, 200, 50, 500 |

Startup fails when `free` or `pro` is missing, a limit is missing or negative, charts exceed 200 or
dashboards 50, the provider is unknown, or (Stripe) the Pro price id is missing. `price-display` is
shown as is.

**Effective plan**: the subscription's plan while its status is `active`, `trialing` or `past_due` and
it comes from the configured provider; Free otherwise (`none`, `incomplete`, `incomplete_expired`,
`unpaid`, `canceled`, `paused`), and always Free with billing off. A subscription set to cancel at
period end stays Pro until the provider reports it `canceled`. A subscription whose price matches no
configured plan counts as Free (logged).

## Provider

`insight.billing.provider` (`BILLING_PROVIDER`): `fake` (default; refused at startup with the `prod`
profile, whose default is `none`), `stripe` or `none`.

- **fake**: state in `fake_billing_objects` (Stripe-shaped JSON). Emits Stripe-format events signed with
  `insight.billing.fake.webhook-secret` (a non-secret local default), after its change is committed,
  by calling the webhook ingestion code with the signed raw body: the signature is verified exactly as
  for a request to `POST /api/billing/webhooks/fake`, and the event goes through the same durable
  pipeline. Development only.
- **stripe**: REST API through Spring `RestClient` (JDK client; connect 5 s, read 20 s), form-encoded,
  `Authorization: Bearer STRIPE_SECRET_KEY`, `Stripe-Version` pinned by `STRIPE_API_VERSION`
  (default `2025-03-31.basil`), `Idempotency-Key` on every POST. Checkout `POST /v1/checkout/sessions`
  (`mode=subscription`, `line_items[0][price]`, `line_items[0][quantity]=1`, `customer`,
  `client_reference_id`, `metadata[business_id]`, `subscription_data[metadata][business_id]`,
  `success_url`, `cancel_url`); customer `POST /v1/customers` (`name`, `metadata[business_id]`);
  portal `POST /v1/billing_portal/sessions` (`customer`, `return_url`); `GET /v1/subscriptions/{id}`
  (period end from `items.data[0].current_period_end`, else top-level `current_period_end`);
  `GET /v1/checkout/sessions/{id}`; cancel `DELETE /v1/subscriptions/{id}` (404/`resource_missing`, or
  a subscription Stripe reports ended, counts as done). **Test mode only**: startup fails unless the key
  starts with `sk_test_` or `rk_test_`; events and objects with `livemode: true` are refused. Errors are
  logged with Stripe's error type/code only, never the key or customer data.
- **none**: billing off. Every business is Free, billing endpoints `404`, limits still enforced.

One customer per business, created at the first checkout (under a lock on the business's billing
row) and reused.

## Webhook pipeline

1. `POST /api/billing/webhooks/{provider}` is public and CSRF-exempt; the body is read raw (max 512 KB).
   The signature (`Stripe-Signature: t=…,v1=…`, HMAC-SHA256 of `"{t}.{raw body}"` with the endpoint
   secret, constant-time compare against every `v1`, `|now − t| ≤ 5 min`, other schemes ignored) must be
   valid, else `400` and nothing is stored.
2. `INSERT … ON CONFLICT (provider, event_id) DO NOTHING` into `billing_events` with references only
   (type, object type/id, subscription id, customer id, business id from metadata). Duplicates are
   no-ops. `200` at once.
3. `BillingEventWorker` (every instance; lease + `FOR UPDATE SKIP LOCKED`) takes due events. Unhandled
   types are `IGNORED`. Otherwise it resolves the subscription (from the event, or the checkout session's
   subscription fetched from the provider), takes an advisory lock for that subscription, **fetches its
   current state from the provider** and writes `business_subscriptions` from it, so order does not
   matter (a late `created` after `deleted` re-reads "canceled").
4. The business is the subscription's `metadata.business_id`. The event is `IGNORED` (and logged) when
   the event names another business, the business does not exist (deleted), the customer or subscription
   is already linked to another business, the business is linked to another customer, the subscription is
   unknown to the provider, its status is unknown, or it is an older subscription superseded by the
   business's current one (an ended or non-entitled subscription never replaces an entitled one).
5. A change of effective plan writes the audit event `billing.plan_changed` `{from, to, status}`
   (actor: system; audit category `billing`) and an info log.
6. Handled types: `checkout.session.completed`, `customer.subscription.created|updated|deleted|paused|
   resumed`, `invoice.paid`, `invoice.payment_failed`.

### Worker settings

`insight.billing.worker.*` (both workers): `enabled` (true), `poll-interval` (`BILLING_WORKER_POLL_INTERVAL`,
`PT2S`), `lease` (`PT2M`), `retry-delay` (`PT30S`), `max-retry-delay` (`PT1H`), `alert-after-attempts` (6).
A failure (provider down, …) is retried after 30 s, doubling up to 1 h, without limit; from the 6th
failure in a row each one is logged as an error. A crashed worker's work is taken over when its lease
ends. Everything needed is in the row, so work survives restarts.

## Business deletion

- The deletion preview (`GET /api/businesses/{id}/deletion-preview`) has an additive field
  **`subscription`**: `{plan, status, message}` or `null`; `message` is "Your Pro subscription will be
  canceled; there is no refund for the current period."
- If the business's subscription is not `canceled`/`incomplete_expired`, the deletion's transaction
  inserts a `billing_cancellations` row. `BillingCancellationWorker` cancels it at the provider
  immediately (no proration), retrying with the same backoff until done; an already canceled or unknown
  subscription counts as done. Webhooks that arrive later for the deleted business are `IGNORED`.
  `business_subscriptions` is removed with the business (cascade).
- If a live subscription shows up later for a deleted business whose customer already has a
  cancellation recorded (e.g. a checkout finished around the deletion), the event worker queues its
  cancellation too.

## Retention

The daily retention job also deletes `billing_events` finished (`PROCESSED`/`IGNORED`) and
`billing_cancellations` `DONE` more than `insight.retention.billing-days` (`RETENTION_BILLING_DAYS`, 30)
days ago. Pending rows are never purged.

## Environment variables (for `infra/.env.example`)

```
# Billing (docs/billing-api.md). fake (default outside prod), stripe (test mode only) or none.
BILLING_PROVIDER=fake
# Stripe (test mode only): STRIPE_SECRET_KEY=sk_test_... or rk_test_...
STRIPE_SECRET_KEY=
STRIPE_WEBHOOK_SECRET=
STRIPE_API_VERSION=2025-03-31.basil
BILLING_PRO_PRICE_ID=
BILLING_PRO_PRICE_DISPLAY=$29 / month
# Optional overrides (defaults shown)
# BILLING_FREE_NAME=Free
# BILLING_FREE_PRICE_DISPLAY=Free
# BILLING_PRO_NAME=Pro
# BILLING_FREE_MAX_MEMBERS=3
# BILLING_FREE_MAX_STORES=2
# BILLING_FREE_MAX_CHARTS=10
# BILLING_FREE_MAX_DASHBOARDS=3
# BILLING_FREE_MAX_IMPORTS_PER_MONTH=10
# BILLING_PRO_MAX_MEMBERS=25
# BILLING_PRO_MAX_STORES=50
# BILLING_PRO_MAX_CHARTS=200
# BILLING_PRO_MAX_DASHBOARDS=50
# BILLING_PRO_MAX_IMPORTS_PER_MONTH=500
# BILLING_WORKER_POLL_INTERVAL=PT2S
# RETENTION_BILLING_DAYS=30
```

`WEB_BASE_URL` (already present) builds every return address.

## Stripe test-mode setup

1. In the [Stripe Dashboard](https://dashboard.stripe.com/test) switch to **Test mode**.
2. **Product catalog → Add product**: "Insight Studio Pro", recurring price (e.g. 29 USD monthly). Copy
   the price id (`price_...`) into `BILLING_PRO_PRICE_ID`, and set `BILLING_PRO_PRICE_DISPLAY` to match.
3. **Developers → API keys**: copy the test secret key (`sk_test_...`), or create a restricted key
   (`rk_test_...`) with write access to Customers, Checkout Sessions, Customer portal and Subscriptions,
   into `STRIPE_SECRET_KEY`. Never commit it; live keys are refused at startup.
4. **Settings → Billing → Customer portal** (test mode): enable it, allow customers to update payment
   methods, view invoices and cancel subscriptions (choose "at end of billing period" or "immediately";
   both are handled), and save.
5. Webhooks in development with the [Stripe CLI](https://docs.stripe.com/stripe-cli):
   `stripe login`, then
   `stripe listen --forward-to localhost:<api port>/api/billing/webhooks/stripe`.
   It prints a signing secret `whsec_...`: put it in `STRIPE_WEBHOOK_SECRET`. For a deployed test
   environment, add an endpoint `https://<api host>/api/billing/webhooks/stripe` under
   **Developers → Webhooks** with the events `checkout.session.completed`,
   `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`,
   `customer.subscription.paused`, `customer.subscription.resumed`, `invoice.paid`,
   `invoice.payment_failed`, and use its signing secret.
6. Start the API with `BILLING_PROVIDER=stripe` and the variables above (startup fails if one is
   missing or the key is not a test key).
7. Test cards ([Stripe testing docs](https://docs.stripe.com/testing)), any future expiry, any CVC:
   - `4242 4242 4242 4242`: succeeds.
   - `4000 0000 0000 0341`: attaches, then fails when charged (use it to simulate failed renewals:
     update the payment method in the portal, then advance a test clock or wait for the renewal).
   - `4000 0000 0000 9995`: declined (insufficient funds) at checkout.
   - `4000 0025 0000 3155`: requires 3-D Secure authentication.
   - [Test clocks](https://docs.stripe.com/billing/testing/test-clocks) simulate renewals, `past_due`
     and cancellation at period end without waiting.
