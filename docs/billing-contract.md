# Subscription billing: contract

Binding agreement between the integrator, the **backend** agent and the **frontend** agent. Change it
only through the integrator. Schema: Flyway `V18__create_billing.sql` (done).

Billing is **per business**: each business is on Free or Pro. Accounts are never billed. The public
demo business is never billed, never limited and stays read-only (§8).

## 1. Plans (configuration)

Plans live in configuration (`insight.billing.plans.<key>.*`), never in the database or the code
beyond defaults. Two plans ship: `free` (the default for every business) and `pro`.

| Limit | Free | Pro | Counted as |
|---|---|---|---|
| `members` | 3 | 25 | memberships **plus open invitations** (an invitation reserves a seat) |
| `stores` | 2 | 50 | rows in `stores` |
| `charts` | 10 | 200 | saved charts |
| `dashboards` | 3 | 50 | custom dashboards |
| `imports-per-month` | 10 | 500 | completed (non-dry-run) imports of any kind this calendar month in the business's time zone (rejected and dry runs don't count) |

Per plan: `name` (display), `price-display` (e.g. `$29 / month`, text shown as is; Free: `Free`),
`provider-price-id` (Pro only: e.g. Stripe `price_...`), and the five limits. A plan limit can never
exceed the existing absolute caps (charts 200, dashboards 50); startup fails if it does, or if a limit
is negative. Defaults above; every value overridable by environment (`BILLING_PRO_PRICE_ID`,
`BILLING_PRO_PRICE_DISPLAY`, `BILLING_FREE_MAX_STORES`, …, documented by the backend).

**Effective plan**: Pro when the business's subscription has plan `pro` and status `active`,
`trialing` or `past_due` (Stripe is still retrying: grace); otherwise Free (`none`, `incomplete`,
`incomplete_expired`, `unpaid`, `canceled`, `paused`). A Pro subscription set to cancel at period end
stays Pro until the provider reports it `canceled`.

## 2. Enforcement (server side, race-free)

Every path that **creates** a limited thing checks the effective plan **inside its transaction, under
a lock per (business, resource)** taken before counting, so concurrent requests can never exceed a
limit (N parallel creations with K slots left → exactly K succeed):

- members: sending an invitation (counts members + open invitations) and **accepting** one (counts
  members only; refused when the business is full, e.g. after a downgrade);
- stores: catalog create **and** store imports (an import that would exceed the limit is refused as a
  whole before writing, with the numbers; a dry run reports it as an error);
- charts: create and duplicate; dashboards: create and duplicate;
- imports: every real import of sales, stores or products.

Refusal: `409` problem detail `{"code": "plan_limit", "resource": "stores", "limit": 2, "used": 2,
"plan": "free", "detail": "The Free plan allows 2 stores. Upgrade to Pro or delete one first."}`
(the detail names the plan and what to do; `upgradeAvailable: true` when a higher plan has a larger
limit). The existing absolute caps keep their messages.

Never limited: reading, running, exporting, editing or deleting existing things; leaving a business;
changing roles; the demo seeder.

## 3. Downgrade, cancellation and payment failure

- **Nothing is ever deleted or hidden by a plan change.** A business above its Free limits after a
  downgrade keeps every member, store, chart, dashboard and import history; all stay readable,
  runnable, editable, exportable and deletable. Only **creating more** of an over-limit resource is
  refused until usage is below the limit (and accepting invitations while members are at or over it).
- Cancel (portal): Pro until the period ends (`cancelAtPeriodEnd: true`, shown with the date), then Free.
- Payment failure: `past_due` keeps Pro (grace while the provider retries) and the Billing page shows
  "Payment failed — update your payment method" with the portal button; `unpaid`/`canceled` → Free.
- `incomplete` (first payment not done/failed) → still Free; `incomplete_expired` → Free.
- Export (business export, account export) and deletion are available on every plan and status.

## 4. Provider adapter

`BillingProvider` interface (backend): `createCustomer(business)`, `createCheckout(business, customer,
plan, successUrl, cancelUrl, idempotencyKey) → url`, `createPortal(customer, returnUrl) → url`,
`fetchSubscription(id) → SubscriptionState`, `cancelSubscription(id)` (immediate, idempotent: an
already canceled or missing subscription counts as done), `expireCheckout(id)` (idempotent),
`verifyWebhook(rawBody, headers) → Event`. Provider calls are never made inside a database transaction;
creating calls are durable operations with idempotency keys (`billing_operations`, V19; see
docs/billing-api.md, "Provider calls").

Selected by `insight.billing.provider` (`BILLING_PROVIDER`): `fake` (default outside `prod`), `stripe`,
or `none` (billing off: every business Free, billing endpoints `404`, limits still enforced).

- **fake**: local, in the API, state in `fake_billing_objects`. Its checkout and portal "pages" are web
  pages (§7) that call fake-only endpoints; it emits webhooks **signed exactly like Stripe**
  (`Stripe-Signature`, its own secret) to the API's own webhook endpoint, through the same durable
  pipeline. Refused (startup fails) with the `prod` profile.
- **stripe**: REST API with the secret key (`STRIPE_SECRET_KEY`), form-encoded, `Idempotency-Key` on
  every POST, `Stripe-Version` pinned in configuration. **Test mode only**: startup fails unless the key
  starts with `sk_test_` or `rk_test_`; events with `livemode: true` are refused. Checkout: `mode=
  subscription`, `line_items[0][price]`, `[quantity]=1`, `customer`, `client_reference_id=<business id>`,
  `metadata[business_id]`, `subscription_data[metadata][business_id]`, `success_url`, `cancel_url`.
  Portal: `POST /v1/billing_portal/sessions {customer, return_url}`. Cancel: `DELETE
  /v1/subscriptions/{id}`. Fetch: `GET /v1/subscriptions/{id}` (period end from
  `items.data[0].current_period_end`, falling back to a top-level `current_period_end`).

## 5. Webhooks

`POST /api/billing/webhooks/{provider}` — public (no session, no CSRF), body read **raw**:

1. Verify the signature (Stripe scheme for both providers: header `t=…,v1=…`, HMAC-SHA256 of
   `"{t}.{raw body}"` with the endpoint secret, constant-time compare against every `v1`, timestamp
   within **5 minutes**; ignore other schemes). Invalid → `400`, nothing stored.
2. Record `(provider, event id)` in `billing_events` with only references (type, object type/id,
   subscription id, customer id, business id from metadata) — `INSERT … ON CONFLICT DO NOTHING`; a
   duplicate delivery is a no-op. Answer `200` at once.
3. A worker (lease + `FOR UPDATE SKIP LOCKED`, any instance) processes due events: it resolves the
   subscription (from the event, or the checkout session's subscription) and **fetches its current
   state from the provider**, then writes `business_subscriptions` from that state. Hence order does
   not matter: a late `created` after `deleted` re-reads "canceled". The business is resolved from the
   subscription's `metadata.business_id` **and** must match the business already linked to that
   customer (if any); an event for an unknown or deleted business is `IGNORED`. Failures (provider
   down) are retried with backoff (30 s doubling to 1 h, without limit; error log from the 6th failure).
4. Handled types: `checkout.session.completed`, `customer.subscription.created|updated|deleted|paused|
   resumed`, `invoice.paid`, `invoice.payment_failed`. Others are recorded and `IGNORED`.
5. Plan changes write audit events (`billing.plan_changed` `{from, to, status}`) and are logged.
   Finished events are purged after 30 days (retention job).

## 6. API

| Method & path | Who | Answer |
|---|---|---|
| `GET /api/businesses/{id}/billing` | ADMIN+ | `Billing` below |
| `POST /api/businesses/{id}/billing/checkout` `{plan: "pro"}` | OWNER, verified | `{url}`; `409` already on Pro (use the portal); `503` provider unavailable |
| `POST /api/businesses/{id}/billing/portal` | OWNER, verified | `{url}`; `409` no billing account yet |
| `POST /api/billing/webhooks/{provider}` | provider (signed) | `200` / `400` |
| `GET /api/billing/plans` | signed in | `[Plan]` (for the comparison table) |
| fake only: `GET /api/billing/fake/{sessionId}`, `POST /api/billing/fake/{sessionId}/{action}` | signed in, OWNER of the session's business | §7 |

`Billing` = `{plan: Plan, subscription: {status, cancelAtPeriodEnd, currentPeriodEnd, canceledAt} | null,
paymentProblem: boolean, provider: "fake"|"stripe"|"none", canManage: boolean (caller is OWNER),
usage: [{resource, used, limit}], plans: [Plan]}`; `Plan` = `{key, name, priceDisplay, limits:
{members, stores, charts, dashboards, importsPerMonth}}`. Never provider secrets; provider ids are not
returned either.

The business in the path is a selector as everywhere (`404` not a member, `403` role). Checkout and
portal URLs come only from the provider (or the fake's own web path); `success_url` / `return_url` are
built from `WEB_BASE_URL` + `/settings/billing?checkout=success|canceled`, never from request input.

## 7. Fake provider flows (web pages)

`/billing/fake/checkout/{sessionId}` — "Test checkout (no real payment)": plan and price, buttons
**Pay with test card** (subscription becomes `active`), **Card declined** (subscription `incomplete`:
still Free, the page says the card was declined and Pay can be tried again), **Cancel** (back to
`/settings/billing?checkout=canceled`).
`/billing/fake/portal/{sessionId}` — "Test billing portal": **Cancel at period end**, **Resume**,
**Cancel now**, **Simulate failed renewal** (`past_due`), **Simulate unpaid** (`unpaid`), **Pay
outstanding invoice** (`active`), **Back to Insight Studio**. Each action changes the fake's state and
emits the matching signed event(s) to the webhook endpoint (tests also replay, duplicate and reorder
them).

## 8. Public demo, deletion, retention

- The configured public demo business: billing endpoints `404`, checkout refused, never limited (it is
  read-only for visitors; its seeder writes directly).
- **Business deletion** (existing flow): if the business has a subscription that is not
  `canceled`/`incomplete_expired`, the deletion transaction inserts a `billing_cancellations` row; a
  worker cancels it at the provider (immediately, no proration), retrying with backoff until done
  (already canceled / missing = done). The deletion preview says "Your Pro subscription will be
  canceled; there is no refund for the current period." Later webhooks for it are `IGNORED`.
- Account deletion is unchanged (last-owner protection means an account never owns a paid business
  when deleted).
- Retention: `billing_events` finished rows 30 days; `billing_cancellations` DONE rows 30 days;
  `business_subscriptions` deleted with the business. Documented in `docs/data-retention.md` by the
  integrator.

## 9. Web app

- **Settings → Billing** (`/settings/billing`, ADMIN+, OWNER manages): current plan and status (badges:
  Active, Cancels on {date}, Payment failed, Canceled), renewal/cancel date, usage meters for the five
  limits (used / limit, over-limit highlighted with the rule "Existing items are kept; you can't add
  more until you're under the limit"), Free vs Pro comparison with `priceDisplay`, **Upgrade to Pro**
  (checkout redirect) and **Manage billing** (portal) for owners; admins see "Only owners can change
  the plan". `?checkout=success`: "Confirming your payment…" and poll `GET …/billing` (every 2 s, up to
  60 s) until Pro, then "You're on Pro"; `?checkout=canceled`: "Checkout canceled. You're still on
  Free." Provider `fake`: a visible "Test mode — no real payments" note.
- Limit errors (`code: plan_limit`) anywhere in the app show the API message plus, for owners/admins, a
  link "See plans" to `/settings/billing`.
- Fake pages of §7 (no sidebar, clear "test" labelling).
- Responsive (390 and 1440 without page overflow), accessible (labelled meters with text values,
  status announced, keyboard reachable).

## 10. Ownership

| Owner | Files |
|---|---|
| **Integrator** | `apps/api/pom.xml`, `db/migration/**` (V18 done), this contract, `README.md`, `infra/**`, `docs/data-retention.md`, merges |
| **backend** agent | `apps/api/**` except the above; `docs/billing-api.md` (incl. Stripe test-mode setup) |
| **frontend** agent | `apps/web/**`; `docs/frontend-billing.md` |
