# Web app: subscription billing

How `apps/web` implements §7 and §9 of `docs/billing-contract.md`. The server is authoritative for
every rule (plans, limits, who may pay); the UI shows the state and hides what the user can't do.

## Routes and navigation

| Route | Who | Page |
|---|---|---|
| `/settings/billing` | OWNER (manages), ADMIN (reads) | `pages/BillingPage.tsx` |
| `/billing/fake/checkout/{sessionId}` | signed in (API: OWNER of the session's business) | `pages/FakeBillingPage.tsx` |
| `/billing/fake/portal/{sessionId}` | same | `pages/FakeBillingPage.tsx` |

Sidebar › Settings gains **Billing** (`lib/settingsAccess.ts#isAdminOrOwner`). Viewers don't see it
and a direct URL shows the existing "You don't have access to this page" state. All three routes
require an account (anonymous → sign-in). The fake pages render outside the workspace, in the
onboarding frame (no sidebar), so they work whichever business is selected.

## API client (`api/billing.ts`)

`billingApi.get|checkout|portal|plans` and `fakeBillingApi.get|act`. Business calls send the path's
id as `X-Business-Id`; plans and fake calls send none (the fake session id selects the business).
The contract does not fix the fake endpoints' JSON or action names; the client assumes:

- `GET /api/billing/fake/{sessionId}` → `{id, kind?, businessId?, businessName?, plan?: {key, name,
  priceDisplay}, status?, subscription?: {status, cancelAtPeriodEnd, currentPeriodEnd, canceledAt}}`
  (every field but `id` optional; the page shows what it gets).
- `POST /api/billing/fake/{sessionId}/{action}` with actions `pay`, `decline`, `cancel` (checkout) and
  `cancel-at-period-end`, `resume`, `cancel-now`, `fail-renewal`, `unpaid`, `pay-outstanding`
  (portal), answering the updated session or an empty body (the page then re-reads the session).
  The names live in `FAKE_CHECKOUT_ACTIONS` / `FAKE_PORTAL_ACTIONS`.

## Settings › Billing

- Loads `GET /api/businesses/{id}/billing`. A `404` means billing is off (provider `none`) and
  shows "Billing is off" with the note "Billing is turned off on this server: every business is on the
  Free plan and its limits still apply." (also shown, with usage and comparison, when the answer says
  `provider: "none"`).
- Provider `fake`: callout **"Test mode — no real payments."** (`.billing-test-mode`).
- `paymentProblem`: alert **"Payment failed — update your payment method"** (`.billing-payment-problem`)
  with **Manage billing** for owners ("Ask an owner to update it." for admins).
- **Current plan** panel: plan name, a plan badge (`Free plan` / `Pro plan`), the status badge from
  `lib/billing.ts#statusBadge` (Active, Trial, Cancels on {date}, Payment failed, Payment incomplete,
  Checkout expired, Paused, Canceled), `priceDisplay`, and the period sentence (`Renews on {date}.`,
  `Pro until {date}, then Free. Nothing is deleted.`, `Canceled on {date}.`). Dates are calendar dates
  in the business time zone.
- Owners: **Upgrade to Pro** (on Free, Pro plan offered; `POST …/checkout {plan: "pro"}` then
  `window.location.assign(url)`, busy "Opening checkout…") and **Manage billing** (once a subscription
  record exists; `POST …/portal`, busy "Opening billing…"). Unverified owners see the buttons disabled
  with "Verify your email address to change the plan." Errors (`409` already on Pro, `503`) show above
  the buttons. Admins see **"Only owners can change the plan."** (`.billing-owner-note`).
- **Usage** panel: one meter per limit (`li.billing-meter.is-ok|is-near|is-full|is-over`), label
  ("Members", "Stores", "Charts", "Dashboards", "Imports this month"), text "2 of 3 stores", a
  `role="meter"` bar with `aria-valuetext` equal to that text. At the limit: badge "Limit reached" and
  a note; over it (after a downgrade): badge "Over limit" and "Over the limit by N. Existing items are
  kept; you can't add more until you're under the limit."
- **Compare plans** panel: table `.billing-compare` (rows Members (incl. open invitations), Stores,
  Saved charts, Dashboards, Imports per month; one column per plan with `priceDisplay`; the current
  plan's column is tinted and badged "Current plan").

### Returning from checkout

`?checkout=success|canceled` is read once when the page opens and removed from the address at once
(`updateQuery`), so a refresh doesn't repeat it. Messages are in a polite live region:

- `success`: "Confirming your payment…" while `GET …/billing` is polled every 2 s; as soon as the
  effective plan is not Free: "You're on Pro. The Pro limits apply now." (and the page reloads its
  data); after 60 s without Pro: "Still confirming your payment — this can take a few minutes.
  Refresh this page later." (`lib/billing.ts#checkoutPollDecision`).
- `canceled`: "Checkout canceled. You're still on Free."

## Fake provider pages (§7)

A dashed amber banner **"Test checkout — no real payment"** / **"Test billing portal — no real
payment"**, then the card titled **"Test checkout (no real payment)"** / **"Test billing portal"**
with the business, plan and price (and the subscription status in the portal).

- Checkout: **Pay with test card** → "Payment succeeded. The subscription is active." and **Return to
  Insight Studio** (→ `/settings/billing?checkout=success`); **Card declined** → "The card was
  declined. The business is still on Free. You can try Pay with test card again." (buttons stay);
  **Cancel** → `/settings/billing?checkout=canceled` (even if the cancel call fails).
- Portal: **Cancel at period end**, **Resume**, **Cancel now**, **Simulate failed renewal**, **Simulate
  unpaid**, **Pay outstanding invoice** (each announces its result and refreshes the status) and
  **Back to Insight Studio** (→ `/settings/billing`).
- Going back selects the session's business first when the user is a member of it.

## Plan-limit errors everywhere

`ApiError.problem` carries the whole problem body. `lib/billing.ts#planLimitOf` recognises
`code: "plan_limit"`, and `components/SeePlans.tsx#SeePlansLink` adds a **See plans** link
(`a.see-plans-link` → `/settings/billing`) for OWNER/ADMIN of the current business. `FormError` and
`ErrorState` take an optional `error` prop that renders it. Wired into: catalog (add store, add
product), store/product/sales imports (`ImportFlow`), chart builder (create), chart page and chart list
(duplicate), dashboards (create dialog, duplicate) and member invitations. A plan-limit `409` is never
mistaken for a "name/title taken" conflict. Accepting an invitation to a full business shows the API
message only (the invitee is not a member of that business yet, so no link).

## Tests

`tests/billing.test.ts`: status badges and period sentences (time zone), usage text/state/percent/notes,
plan-limit detection and the "See plans" role rule, `?checkout=` parsing and the polling decision.
