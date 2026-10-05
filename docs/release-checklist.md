# Release checklist — v1.0.0-rc.1

A release is three separate decisions. Each has its own checklist; later ones assume the earlier ones are
done. The **open release checks** they refer to are tracked in [release-checks.md](release-checks.md) and stay
open until their own "To close" steps are done: a test that passes only after a rerun, or a run that happens
to be green, does not close them.

| Stage | What it means | Defaults that must stay | Open checks that block it |
|---|---|---|---|
| **A. Base release** | The app for invited teams; no public demo, no paid plans | `REPORTS_ENGINE=sql`, `BILLING_PROVIDER=none`, `SPRING_PROFILES=prod` | none (Cube and billing stay off) |
| **B. Public demo** | Also show the read-only demo business to signed-out visitors | as A, plus `SPRING_PROFILES=prod,demo` | none |
| **C. Paid SaaS** | Charge for Pro plans through Stripe | as A; `BILLING_PROVIDER=stripe` only after this checklist | **Real Stripe sandbox** (open) |

Cube (`REPORTS_ENGINE=cube`) is not part of any stage of this release: it stays off until its open check
(intermittent failures, upstream image vulnerabilities) is closed.

## A. Base release

**Build and verify**

- [ ] CI is green on the release commit (backend shards a and b, Cube checks, web, images, configuration,
      dependencies). The Cube job's "flaky" summary is reviewed: entries are recorded under the open Cube
      check, not treated as passed.
- [ ] Images built from the release commit and tagged with it (`git rev-parse --short=12 HEAD`):
      `docker build -t insight-api:<tag> apps/api`, `docker build -t insight-web:<tag> apps/web`.
- [ ] `bash scripts/ci/image-smoke.sh <tag>` passes: probes on the management port only, JSON logs, **reports
      on the SQL engine, billing off**.
- [ ] Trivy reports no fixable HIGH/CRITICAL in the API and web images (the CI image job).
- [ ] `project.build.outputTimestamp` in `apps/api/pom.xml` is set to the release date.

**Production defaults** (verified by `ProductionProfileIntegrationTest.productionDefaultsKeepCubeBillingAndThePublicDemoOff`
and the image smoke test)

- [ ] `infra/.env.prod`: `REPORTS_ENGINE=sql` (or unset), `BILLING_PROVIDER=none` (or unset), no
      `INSIGHT_CUBE_URL`, `SPRING_PROFILES=prod` (or unset), no `STRIPE_*` values.
- [ ] `WEB_BASE_URL` is the public `https://` address; a TLS proxy with HSTS is in front of `WEB_PUBLISH`;
      `TRUSTED_PROXIES` names only the web container (172.30.240.10).
- [ ] A real SMTP server (`MAIL_HOST`, `MAIL_FROM`, credentials); a test email reaches an inbox.
- [ ] Secrets only in `infra/.env.prod` on the host (mode 600) or your secret store; nothing in git.

**Data safety**

- [ ] Backups scheduled daily and copied off the host ([backup-restore.md](backup-restore.md)).
- [ ] The restore drill passes on the latest backup (`infra/backup/restore-drill.sh`).
- [ ] For an upgrade: a backup taken just before it, and the previous image tags written down for rollback
      ([deployment.md](deployment.md)).

**Operations**

- [ ] `--profile monitoring` (or your own Prometheus) scrapes `api:8081`; the alert rules are loaded and routed
      to someone ([operations.md](operations.md)).
- [ ] After deploying: `/actuator/health/readiness` UP, `/actuator/health/jobs` UP, a sign-up/sign-in/business
      creation works through the public address, `/actuator` answers 404 through the web proxy.
- [ ] Load test baseline compared with the last run on the same hardware ([load-testing.md](load-testing.md)),
      if the release changed reporting, imports or workers. (Advisory: the webhook p95 check is open.)

## B. Public demo (in addition to A)

The demo business (Fieldstone Apparel Co.) is fictional and seeded once; visitors can read it without an
account and can never change it.

- [ ] `SPRING_PROFILES=prod,demo` in `infra/.env.prod`; restart the API.
- [ ] Signed out, the overview, products, sales, stores and reports of the demo business load. Saved charts,
      custom dashboards and every write (imports, catalog, settings, members, billing) answer 401 and the UI
      offers sign-in.
- [ ] The demo business has **no members** (`SELECT count(*) FROM memberships m JOIN businesses b ON b.id =
      m.business_id WHERE b.slug = 'fieldstone-apparel'` → 0). A member would let someone change what every
      visitor sees.
- [ ] Billing stays off for the demo business whatever the billing stage (it is never billed and never limited).
- [ ] Rate limits are in place (they are on by default) and the public address is behind your proxy's
      abuse protection.
- [ ] The screenshots in `docs/screenshots/` and any marketing material use only the fictional demo data.

## C. Paid SaaS activation (in addition to A)

Do **not** switch billing on until every item below is done. Until then `BILLING_PROVIDER=none` and the
Billing page says billing is off.

**Close the open "Real Stripe sandbox" check first** ([release-checks.md](release-checks.md), item 2): run every
listed flow against a Stripe test-mode account and record the results there.

- [ ] Stripe account verified for payouts; products and prices created; the Pro price id noted.
- [ ] A Stripe **test-mode** pass of the whole flow on a staging deployment of this release:
      `BILLING_PROVIDER=stripe`, `STRIPE_SECRET_KEY=sk_test_…`, `STRIPE_WEBHOOK_SECRET`, `BILLING_PRO_PRICE_ID`,
      webhooks delivered to `https://<staging>/api/billing/webhooks/stripe`. Checkout paid and declined, 3-D Secure,
      portal cancellations, a failed renewal, a replayed webhook, deleting a business with a live subscription
      and with an open checkout.
- [ ] Customer portal configured (payment methods, invoices, cancellation policy) in Stripe.
- [ ] Plan limits and prices reviewed (`BILLING_*` settings; `BILLING_PRO_PRICE_DISPLAY` matches the Stripe price).
- [ ] Terms of service, privacy policy (data-retention.md covers retention; Stripe is a processor), refund
      policy, tax handling (Stripe Tax or your accountant) decided and published.
- [ ] Alerts `InsightBillingCancellationStuck` and `InsightJobFailing` route to someone who can act on payments.
- [ ] **Live mode is a code change, on purpose**: the API refuses `sk_live_` keys and live-mode events
      (`StripeBillingProvider`). Lifting that guard is a separate, reviewed change after the test-mode pass;
      it is not done in this release.

## Release candidate record (v1.0.0-rc.1)

| Item | Result |
|---|---|
| Production defaults | verified: SQL reports, no Cube, billing off, no public demo (integration test and image smoke test) |
| Open release checks | **still open**: intermittent Cube failures, real Stripe sandbox, webhook p95 under load (advisory) |
| Screenshots | `docs/screenshots/` from the production web build on the fictional demo data |
| Pushed / deployed | no |
