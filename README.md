# Insight Studio

A retail analytics dashboard for a multi-store clothing business. A Spring Boot API computes
sales metrics from PostgreSQL; a React app presents revenue trends, store performance, top
products and recent sales, plus a searchable product catalogue with per-product sales history,
a sales register with receipt-level detail, per-store performance and monthly/category reports
with CSV and PDF export, and saved report definitions that can be re-run with fixed or rolling
dates. Date-range and store filters apply across every page.

People sign up, create businesses and invite others by email as owners, admins or viewers.
Every request sees only the business its signed-in user is a member of; owners and admins can set
up stores and products and import historical sales from CSV. An optional, private Cube semantic
layer serves the same figures behind the API, and a read-only public demo can be switched on.

The bundled demo business, **Fieldstone Apparel Co.**, and all of its stores, products and sales
are fictional.

## Architecture

```
apps/
  api/        Spring Boot 4 (Java 21) REST API — JPA, Flyway, PostgreSQL
  web/        React 19 + TypeScript + Vite dashboard
infra/        Docker Compose for local PostgreSQL 16
services/
  analytics/  Cube semantic layer (optional; reconciled against the API)
docs/         feature notes: accounts, stores, reports, CSV import, analytics
```

```
Browser ──> Vite dev server (:5173) ──/api proxy──> Spring Boot API (:8080) ──> PostgreSQL (:5435)
```

**Data model** (Flyway migrations in `apps/api/src/main/resources/db/migration`):

| Table | Purpose |
|---|---|
| `businesses` | Name, slug, currency (ISO 4217), reporting time zone |
| `stores` | Physical or online stores of a business; unique `(business_id, code)` |
| `products` | Catalogue with SKU, category and **current** list price |
| `sales` | One receipt: store, receipt number, `sold_at` timestamp |
| `sale_items` | Product, quantity and the **unit price actually charged** |
| `import_batches` | CSV imports (V3): file name, content hash (unique per business), counts and total; imported sales point to their batch through `sales.import_batch_id` |
| `users`, `business_memberships` | Accounts (V4): email, bcrypt hash, session version; one role (OWNER, ADMIN, VIEWER) per user and business |
| `password_reset_tokens` | Recovery (V4): SHA-256 of a single-use token, 30-minute expiry |
| `spring_session`, `spring_session_attributes` | Sessions (V5), shared by every API instance and kept across restarts |
| `rate_limit_hits` | Rate limits (V6): hashed buckets for sign-in, sign-up, recovery and invitations |
| `invitations` | Invitations (V7): SHA-256 of a single-use token, 7-day expiry, invited email and role |
| `mail_outbox` | Account emails waiting to be sent (V8); bodies erased once sent or abandoned |
| `email_verification_tokens` | Email verification (V9): SHA-256 of a single-use token, 24-hour expiry; `users.email_verified_at` |
| `saved_reports` | Saved report definitions (V10): name, kind, fixed dates or a relative preset, optional store of the same business (composite foreign key) |

Revenue is always `SUM(quantity × sale_items.unit_price)`, so historical revenue is unaffected when
a product's list price changes. Days, weeks and months are bucketed in the business's time zone.

**Backend design**

- Schema is owned by Flyway; Hibernate runs with `ddl-auto=validate`.
- Reporting queries use `NamedParameterJdbcTemplate`: each endpoint runs one aggregate SQL query
  per result (no N+1), scoped to the business, a half-open time window and an optional store.
- The `reporting` package holds what the dashboard, product, sales, store and report endpoints share: business
  resolution, date/store filter defaults and validation, SQL filter fragments and bucket math, so
  filters behave identically everywhere.
- The business comes from the signed-in user's membership (`tenancy.CurrentBusiness`), never from
  a client-supplied id alone; writes also check the member's role. See
  [docs/auth.md](docs/auth.md).
- User input never reaches SQL text: sort columns and date buckets come from enums, and search
  terms are bound parameters with `LIKE` wildcards escaped.
- Responses are Java records (DTOs); JPA entities are never serialized.
- Errors are RFC 9457 problem details (`application/problem+json`) with a readable `detail`.

**Frontend design**

- Plain CSS with design tokens (navy, soft gray, restrained blue); no UI framework.
- [Recharts](https://recharts.org) renders the revenue chart. It is React-native (declarative
  components, React 19 support) and handles responsive sizing, tooltips and axis ticks, which are
  the fiddly parts of hand-built SVG. Sales by store is plain HTML/CSS bars.
- Each panel loads independently with skeleton, empty and error states.
- A small History API router (no routing dependency) serves every page listed below. The
  store and date filters are shared by all pages and kept in the URL, along
  with page-specific state such as search, sort and page number, so views can be bookmarked and
  the back button restores them. Deep links work with the Vite dev and preview servers, which fall
  back to `index.html`; a static host needs the same fallback configured.

## Pages

| Path | Shows |
|---|---|
| `/` | Dashboard: metric cards, revenue over time, sales by store, top products, recent sales. Top-product rows open the product page; recent-sale rows open the receipt |
| `/products` | Catalogue: every product with current price, average price actually charged, units, orders and revenue for the period. Search by name or SKU, filter by category, sort, 20 per page |
| `/products/{id}` | Product: period metrics vs the previous period, sales trend at the prices charged, and each distinct price the product sold at, compared with today's list price. Links to the receipts containing the product |
| `/sales` | Sales register: every receipt in the period with local date and time, store, items, units and total. Search by receipt number, filter to receipts containing a product, sort newest, oldest or largest, 25 per page |
| `/sales/{id}` | Receipt: date and time, store, and each line's product, quantity, unit price charged (compared with today's list price) and line total |
| `/stores` | Every store's revenue, share, change vs the previous period, orders, units and average order value, with an all-stores total |
| `/stores/{id}` | Store: metrics vs the previous period, revenue trend, category mix and top products. Store pages ignore the global store filter |
| `/reports` | Monthly and Categories reports (tab kept in the URL) with totals rows that equal the dashboard, partial months marked, CSV and PDF export, and "Save report" (owners and admins) |
| `/reports/saved`, `/reports/saved/{id}` | Saved reports: run, export (CSV, PDF) for every member; rename, edit and delete for owners and admins |
| `/imports`, `/imports/{id}` | CSV import (owners and admins): validate (dry run) with a line-by-line error table, import, and history |
| `/sign-in`, `/sign-up`, `/forgot-password`, `/reset-password`, `/verify-email` | Accounts, recovery and email verification |
| `/businesses/new` | Create a business (shown after sign-up when you have none); the sidebar switches between your businesses |
| `/settings/catalog` | Add stores and products (owners and admins) |
| `/settings/members` | Members, roles and invitations (owners and admins) |
| `/invite` | Accept an invitation from its emailed link |
| `/account` | Profile, password change and sign-out |

Signed-out visitors see the read-only demo when it is enabled, otherwise the sign-in page. Viewers
and the demo don't see Import, Catalog or Members. Frontend details:
[docs/frontend-accounts.md](docs/frontend-accounts.md).

**Orders, items and units:** an *order* is a receipt with at least one line item. The schema
allows a receipt without items, but it has no revenue or units, so it is left out of every list,
count, average and date range; the Sales list count therefore always equals the dashboard's order
count for the same filters. Such a receipt can still be opened at `/sales/{id}`, which says it is
not counted. *Items* are the distinct products on a receipt (line items); *units* are the total
quantity. The schema records no customer, payment or discount data, so none is shown.

## Requirements

- Java 21
- Node.js 20.19+ or 22.12+ (developed with Node 24) and npm
- Docker Desktop (PostgreSQL locally, and Testcontainers for backend tests)

## Local setup (Windows Command Prompt)

Run each block in its own Command Prompt window, starting from the repository root.

**1. PostgreSQL** (first time: create `infra\.env` and set your own `POSTGRES_PASSWORD`)

```bat
cd infra
copy .env.example .env
notepad .env
docker compose up -d
```

Later runs only need `cd infra` and `docker compose up -d`. The database listens on
`127.0.0.1:5435`; the same command starts Mailpit, which catches account emails (SMTP
`127.0.0.1:1025`, inbox at http://localhost:8025).

**2. API with demo data** (http://localhost:8080)

```bat
cd apps\api
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=demo
```

The API reads the database name, user and password from `infra\.env`, so no password is passed on
the command line. To connect elsewhere, set `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`, which take
precedence:

```bat
set DB_URL=jdbc:postgresql://localhost:5435/insight_studio
set DB_USERNAME=insight
set DB_PASSWORD=your-password
.\mvnw.cmd spring-boot:run
```

**3. Web** (http://localhost:5173)

```bat
cd apps\web
npm install
npm run dev
```

**4. First account.** Open http://localhost:5173/sign-up and create an account (passwords are 12 to
72 characters). Open the verification link from the email in Mailpit (http://localhost:8025), sign
in, then create a business; you become its owner. (Until the address is verified you can sign in and
look around, but not create or change a business.) Add stores and products under
Catalog, then import sales under Import. With the `demo` profile, signed-out visitors browse the
demo business read-only; without it they are sent to sign in.

To invite someone, open Members and send an invitation; they get an email with a link to join.
Verification, password-reset and invitation emails go through a persistent outbox (retried if the
mail server is down, kept across restarts) to Mailpit, the local mail catcher started by
`docker compose up -d`: read them at http://localhost:8025. Nothing leaves your machine, and links
are never written to the API log. Account settings:

| Environment variable | Default | Purpose |
|---|---|---|
| `WEB_BASE_URL` | `http://localhost:5173` | Where the web app is served; email links point to its `/reset-password` and `/invite` pages |
| `MAIL_HOST`, `MAIL_PORT` | `localhost`, `1025` (Mailpit) | SMTP server for account emails; also `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH`, `MAIL_STARTTLS`, `MAIL_FROM` |
| `COOKIE_SECURE` | `false` | Mark session and CSRF cookies `Secure` (forced on by the `prod` profile) |
| `TRUSTED_PROXIES` | empty | Reverse proxies whose `X-Forwarded-For`/`-Proto` headers are believed (IPs or CIDR ranges) |

For deployments (the `prod` profile, HTTPS, reverse proxies, SMTP) see
[docs/production.md](docs/production.md).

**CSV import** is available to owners and admins of their own business, never to the public demo.
File format, rules and a sample file: [docs/csv-import.md](docs/csv-import.md) and
[docs/sample-import.csv](docs/sample-import.csv).

**Optional: Cube analytics** (private; production mode, bound to `127.0.0.1:4000`). Set
`CUBEJS_API_SECRET` in `infra\.env` (see `infra\.env.example`), then:

```bat
cd infra
docker compose --profile analytics up -d
```

and start the API with `INSIGHT_CUBE_URL=http://localhost:4000`. The browser never talks to Cube:
the API signs a 60-second token carrying the member's business id, and Cube adds that business
filter to every query. Add `REPORTS_ENGINE=cube` to have Cube compute the monthly and category
reports (saved reports and CSV/PDF exports included) instead of SQL; figures are checked against the
latest data change, so an import shows up in the next report, and if Cube cannot answer in time the
API says so (`503`) rather than showing stale numbers. See [docs/cube-reports.md](docs/cube-reports.md). Plain `docker compose up -d` starts only PostgreSQL and Mailpit. Model, security
and the reconciliation script: [docs/analytics.md](docs/analytics.md).

## Demo data

Demo data is loaded **only** when the `demo` Spring profile is active
(`-Dspring-boot.run.profiles=demo`, or `SPRING_PROFILES_ACTIVE=demo`). Without that profile the
seeder bean does not exist, so a normal or production start never inserts sample data. A test
asserts this.

- **Contents:** 1 business (USD, America/New_York), 4 stores (Boston, Cambridge, Providence,
  Online), 24 products in 7 categories, and 10,397 sales / 17,993 line items from
  2026-03-01 to 2026-08-31.
- **Realistic pricing:** some list prices rise on 2026-06-01, and outerwear and knitwear are 30%
  off from 2026-07-15. That shows historical prices being kept.
- **Deterministic:** a fixed random seed and fixed dates produce identical data every time. A test
  pins the exact counts and revenue total.
- **Idempotent:** if the demo business (`fieldstone-apparel`) already exists, seeding is skipped,
  so restarting never duplicates data.
- **Public and read-only:** the profile also sets `insight.demo.public=true`, so signed-out
  visitors can read (never write to) the demo business. The demo business has no members; a
  business with members is never served as the demo.

To reload from scratch, remove the database volume. **This deletes all local data.**

```bat
cd infra
docker compose down -v
docker compose up -d
```

## API

**Authentication.** Sessions are stored in PostgreSQL behind an HttpOnly `SESSION` cookie. Every `POST`, `PATCH` and
`DELETE` needs the `X-XSRF-TOKEN` header copied from the `XSRF-TOKEN` cookie (issued by
`GET /api/session`). Signed-in users with several businesses choose one with `X-Business-Id`; the
server checks the membership and answers `404` for any business they don't belong to. Signed-out
requests get the public demo when it is enabled, otherwise `401`. Full rules, role matrix and a
curl walkthrough: [docs/auth.md](docs/auth.md).

| Account endpoint | Does |
|---|---|
| `GET /api/session` | Current user, memberships and demo info; issues the CSRF cookie |
| `POST /api/auth/sign-up`, `/sign-in`, `/sign-out` | Accounts and sessions (sign-up always answers `202`, whether or not the address has an account; both are rate limited) |
| `POST /api/auth/verify-email`, `/verify-email/resend` | Verify an address from its emailed link; send a new link |
| `POST /api/auth/password/change`, `/password/forgot`, `/password/reset` | Password change and recovery (single-use tokens, 30 minutes) |
| `GET`, `POST /api/businesses`; `PATCH /api/businesses/{id}` | Your businesses; create one (you become OWNER); rename (OWNER) |
| `/api/businesses/{id}/members[/{userId}]` | List, change role, remove (owners and admins, with last-owner protection) |
| `/api/businesses/{id}/invitations[/{invitationId}]` | Invite by email, list open invitations, revoke (owners and admins) |
| `POST /api/invitations/preview`, `/accept` | Show an invitation from its token; accept it (signed in, invited address only) |
| `POST /api/stores`, `POST /api/products` | Create stores and products (OWNER or ADMIN) |

The reporting endpoints below are `GET` and read-only. Common query parameters:

| Parameter | Format | Default |
|---|---|---|
| `from`, `to` | inclusive ISO dates `yyyy-MM-dd`, in the business time zone | `to` = last day with sales (or today); `from` = 30 days ending at `to` |
| `storeId` | positive integer | all stores |

Ranges may span at most 1,098 days. `from` must not be after `to`. An unknown store returns
`404`; invalid parameters return `400`.

| Endpoint | Returns |
|---|---|
| `/api/dashboard/context` | Business (name, currency, time zone), stores, first/last sale dates |
| `/api/dashboard/summary` | Revenue, orders, units, average order value, each with the previous equal-length period and % change |
| `/api/dashboard/revenue?granularity=day\|week\|month` | Revenue and orders per bucket, zero-filled; partial edge buckets are flagged. Granularity is chosen from the range length if omitted |
| `/api/dashboard/sales-by-store` | Revenue, orders, units and revenue share per store (stores without sales included) |
| `/api/dashboard/top-products?limit=5` | Best sellers by revenue with units and average price charged (`limit` 1–50) |
| `/api/dashboard/recent-sales?limit=10` | Latest receipts with store, units and total (`limit` 1–50). The `itemCount` field holds units (total quantity); the name predates the sales API |
| `/api/products` | One page of the catalogue with sales performance for the period; products without sales are included (see below) |
| `/api/products/categories` | Distinct product categories, for the category filter |
| `/api/products/{id}` | Product details; revenue, units, orders and average selling price vs the previous period; price history (each unit price charged, with first/last date, units and orders) |
| `/api/products/{id}/sales-trend?granularity=day\|week\|month` | Revenue, units, orders and average price charged per bucket, zero-filled, with partial buckets flagged |

`/api/products` also accepts:

| Parameter | Format | Default |
|---|---|---|
| `q` | text, up to 100 characters; case-insensitive match on name or SKU (`%` and `_` match literally) | none |
| `category` | exact category name | all |
| `sort` | `revenue`, `units`, `name`, `sku` or `price` (current list price) | `revenue` |
| `direction` | `asc` or `desc` | `desc` for revenue, units and price; `asc` for name and sku |
| `page` | zero-based page number, 0–10,000 | `0` |
| `size` | page size, 1–100 | `20` |

The response includes `totalItems` and `totalPages`. A page past the end returns an empty
`items` list. `averageSellingPrice` is `null` when a product sold nothing in the period. Products
of other businesses return `404`.

| Sales endpoint | Returns |
|---|---|
| `/api/sales` | One page of orders (receipts with at least one item) in the period, with `lineCount` (items), `unitCount` and `total` at the prices charged |
| `/api/sales/{id}` | One receipt: store, `soldAt`, and lines with `quantity`, `unitPrice` (charged), `lineTotal` and `currentListPrice` (today's, for comparison) |

`/api/sales` also accepts:

| Parameter | Format | Default |
|---|---|---|
| `q` | text, up to 40 characters; case-insensitive match within the receipt number (`%` and `_` match literally) | none |
| `productId` | only receipts containing this product; totals still cover the whole receipt | all |
| `sort` | `newest`, `oldest` or `largest` (total) | `newest` |
| `page` | zero-based page number, 0–10,000 | `0` |
| `size` | page size, 1–100 | `25` |

The list contains orders only (receipts with at least one line item), so `totalItems` equals
`orders` from `/api/dashboard/summary` for the same filters and the listed totals add up to its
revenue. `/api/sales/{id}` also returns receipts without items, with empty `lines`. The sale
detail is not limited by the date or store filters. Sales, and products used as filters,
belonging to other businesses return `404`.

| Stores and reports | Returns |
|---|---|
| `/api/stores` | Every store with revenue, orders, units, average order value, revenue share and change vs the previous period |
| `/api/stores/{id}`, `/api/stores/{id}/revenue`, `/api/stores/{id}/top-products` | Store metrics vs the previous period with category mix; revenue series and top products (same shapes as the dashboard) |
| `/api/reports/monthly`, `/api/reports/categories` | Monthly rows (partial months flagged, month-over-month change) and category rows, each with totals that equal the dashboard summary |
| `/api/reports/monthly.csv`, `/api/reports/categories.csv` | The same reports as RFC 4180 CSV downloads, protected against formula injection |
| `/api/reports/monthly.pdf`, `/api/reports/categories.pdf` | The same reports as A4 PDFs (business, period, time zone, filters, metrics, table with totals, generation time, page numbers), built from the same figures |
| `/api/saved-reports[/{id}]` | Saved report definitions: list and get (any member), create, replace/rename, delete (owners and admins) |
| `/api/saved-reports/{id}/report[.csv\|.pdf]` | Run a saved report for its range resolved today in the business time zone; CSV and PDF exports |

Details: [docs/stores.md](docs/stores.md), [docs/reports.md](docs/reports.md),
[docs/saved-reports-api.md](docs/saved-reports-api.md), [docs/frontend-saved-reports.md](docs/frontend-saved-reports.md).

**CSV import** (OWNER or ADMIN; viewers get `403`, signed-out visitors `401`): `POST /api/imports`
(multipart `file`, `dryRun` default `true`) returns `VALIDATED`, `IMPORTED` or `REJECTED` with
counts, total and line-level errors; `GET /api/imports` and `/api/imports/{id}` list and show
batches. Imports are all-or-nothing, never overwrite an existing receipt, and reject a file
already imported into the same business (by content hash). The dashboard context reports what the
caller may do in `access` (`role`, `canImport`, `canManageCatalog`, `canManageMembers`,
`readOnly`). See [docs/csv-import.md](docs/csv-import.md).

**Analytics** (`GET /api/analytics/summary`): the same summary figures served by Cube for the
member's business; `503` when `INSIGHT_CUBE_URL` is not set, `502` if Cube fails. See
[docs/analytics.md](docs/analytics.md).

Example error:

```json
{ "status": 400, "title": "Bad Request", "detail": "'from' (2026-09-01) must be on or before 'to' (2026-08-01).", "instance": "/api/dashboard/summary" }
```

## Tests and checks

```bat
cd apps\api
.\mvnw.cmd verify

cd ..\web
npm run lint
npm run build
```

Backend tests start a throwaway PostgreSQL 16 container with Testcontainers, so Docker must be
running. They do not touch your local database. The tests cover:

- metric and bucket calculations
- dashboard API responses against a hand-computed dataset, including time-zone day boundaries,
  historical prices, store filters, partial buckets and cross-business isolation
- product API: search (including literal wildcards), category and store filters, sorting,
  paging, price history, trends, and products of other businesses
- sales API: local-date windows, store and product filters, receipt search, sorting, paging,
  line totals at historical prices, and sales of other businesses
- the order definition: receipts without items are excluded from the Sales list, dashboard
  store counts and the data range, and the Sales list count and totals match the dashboard
- stores and reports: every figure reconciles with the dashboard summary for the same filters;
  month boundaries in the business time zone; CSV quoting and formula-injection escaping
- CSV import: parser edge cases, every validation rule, business-scoped duplicate receipts and
  file hashes, dry runs and rejected files write nothing, atomic writes, and dashboard and Sales
  figures moving by exactly the imported totals
- accounts: sign-up, sign-in, sign-out, rate limits, password change and recovery (single-use,
  expiring tokens that end existing sessions), CSRF and session fixation
- roles: every endpoint against OWNER, ADMIN, VIEWER, the public demo and signed-out callers,
  including last-owner protection
- business isolation: two businesses with their own members; neither can read or modify the
  other's dashboard, products, sales, stores, reports, imports, members or analytics, whatever
  ids or `X-Business-Id` they send
- Cube reports (real PostgreSQL, Cube Store and Cube containers): the Cube engine's JSON equals the
  SQL engine's and an independent SQL query for six businesses in four time zones (local midnight,
  month ends, DST changes, stores, empty periods, price changes, receipts without items), CSV/PDF
  equality, an import or catalog change visible in the very next report, and 503 answers when Cube
  is stopped or behind
- Cube: tokens carry only the member's business, and Cube is never called for a refused request
  (plus `node --test` in `services/analytics` for the Cube-side filter)
- validation and error responses
- the demo seeder and the public demo switch
- invitations: who may invite and revoke, identical answers for addresses with and without an
  account, single use, expiry, revocation, the invited address only, an inviter who lost the role
- sessions: shared by two API instances, surviving a restart, ended everywhere by sign-out or a
  password change, and stored as plain values only
- rate limits: sign-up, sign-in, recovery and invitations; shared across instances and restarts,
  sliding windows, and no bypass by concurrent requests or spoofed `X-Forwarded-For`
- trusted proxies, and the `prod` profile (Secure `__Host-` cookies; refuses unsafe settings)
- email: verification, reset, notice and invitation emails delivered over real SMTP to a Mailpit
  container, and never written to the log
- email verification: single use, expiry, replaced links, a link verifying only its own account,
  unverified accounts refused every business write, invitations and resets verifying the address,
  and sign-up answering the same for existing addresses without changing them
- saved reports: every endpoint against OWNER, ADMIN, VIEWER, unverified and signed-out callers;
  another business's definitions and stores answer 404 everywhere, including exports; every
  relative preset at month, quarter, year and leap-year boundaries and in time zones a day apart
- PDF export: totals equal the JSON totals and the CSV rows, a saved run equals the ad-hoc report,
  long tables break over pages with the header repeated, empty periods say so
- mail outbox: emails queued while the mail server is down sent after a restart, a dead worker's
  lease taken over, bounded retries ending in FAILED with the body erased, expired links dropped,
  two workers never sending an email twice

## Roadmap

**Done:**

- Stores: per-store performance list and store detail pages
- Reports: on-demand monthly and category reports with CSV export
- Saved reports (fixed or rolling date ranges, per store) and PDF export of every report
- Accounts, businesses and roles (owner, admin, viewer) with server-side authorization and
  business isolation on every endpoint; password recovery by email
- Email invitations; sessions and rate limits in PostgreSQL for several API instances; trusted
  proxy handling and a `prod` profile that requires HTTPS settings
- Email verification, sign-up that never reveals existing accounts, and a persistent mail outbox
  with bounded retries
- CSV import for owners and admins of their own business (never the public demo)
- Private Cube analytics behind the API, scoped to the member's business
- Reports computed by Cube (selectable; SQL kept during the migration) with verified freshness

**Later:**

- Account deletion and email address changes
- Business settings UI (rename, time zone)
- Scheduled report emails (saved reports sent through the mail outbox)
- Make Cube the default report engine, then retire the SQL report queries
- Dashboard panels served from Cube
- Forecasting in `services/analytics`
- Per-store breakdown on the product page
- Product mix over time
- PDF export of the dashboard view
- Billing
- Deployment (containerized API + static web build)
