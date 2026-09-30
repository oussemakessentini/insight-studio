# Accounts, roles and business isolation — contract

This is the shared contract for the accounts phase. Agents build against it; changes go through
the integrator. No billing in this phase.

## 1. Authentication approach

- **Server-side sessions with Spring Security**, not JWTs in the browser. The web app and API are
  same-origin (Vite proxies `/api`), so an `HttpOnly` session cookie (`JSESSIONID`,
  `SameSite=Lax`, `Secure` when served over HTTPS) is simpler and safer than tokens in
  `localStorage`, and sign-out/revocation is immediate.
- **CSRF protection is on** for every state-changing request. The API sets a readable `XSRF-TOKEN`
  cookie; the web app echoes it in an `X-XSRF-TOKEN` header on `POST`/`PUT`/`PATCH`/`DELETE`
  (Spring Security's SPA CSRF setup). `GET /api/session` always issues the cookie.
- **Passwords**: Spring Security `DelegatingPasswordEncoder` (bcrypt). Policy: 12–128 characters,
  must not equal the email. Emails are trimmed and compared case-insensitively.
- **Session fixation**: the session id changes on sign-in. Sessions are in memory (single
  instance; lost on API restart — documented). Each user has a `session_version`; a password
  reset or change increments it and any session carrying an older version is rejected, so a
  reset signs out every other session.
- **Brute force**: at most 5 failed sign-ins per email and 20 per client IP in 15 minutes, then
  `429` with `Retry-After`. In-memory, per instance.
- **Account recovery**: `POST /api/auth/password/forgot` always answers `202` (no account
  enumeration). A random 32-byte token (URL-safe base64) is emailed as a link; only its SHA-256 is
  stored; it expires after 30 minutes and is single-use. There is no mail server yet: the
  `PasswordResetNotifier` implementation used in development logs the link at INFO; tests capture
  it. A real mail notifier is later work.
- **Sign-up** returns `409` for an email that already has an account (documented trade-off: this
  reveals that the email is registered; sign-in and recovery do not).

## 2. Schema (Flyway `V4__create_accounts.sql`, owned by the backend-auth agent)

- `users`: `id`, `email VARCHAR(254) NOT NULL` with a unique index on `lower(email)`,
  `password_hash VARCHAR(100) NOT NULL`, `display_name VARCHAR(100) NOT NULL`,
  `session_version INTEGER NOT NULL DEFAULT 0`, `created_at`, `last_sign_in_at NULL`.
- `memberships`: `(user_id, business_id)` primary key, both FKs, `role VARCHAR(10) NOT NULL`
  `CHECK (role IN ('OWNER','ADMIN','VIEWER'))`, `created_at`; index on `business_id`.
- `password_reset_tokens`: `id`, `user_id` FK, `token_sha256 CHAR(64) UNIQUE`, `expires_at`,
  `used_at NULL`, `created_at`.
- No change to `businesses`: the public demo business is chosen by configuration (slug), not by
  a column. Existing businesses keep working; a business without members is reachable only as the
  public demo (if configured) — never by another business's members.

## 3. Business context and isolation (the core rule)

Every business-scoped request resolves **one** business through
`tenancy.CurrentBusiness.require(...)`, and every query is filtered by that business id.
`ReportingContext.currentBusiness()` delegates to it, so all existing services inherit isolation.

Resolution:

1. **Authenticated user**: the client may send `X-Business-Id: <id>` to choose among its
   memberships. The id is only a *selector*: the server loads the membership for
   `(authenticated user, id)`; if none exists the answer is **404 "Business not found."** (not 403,
   so other businesses' ids are not confirmed). Without the header: the user's only membership, or
   `400 "Select a business (X-Business-Id)."` if there are several, or `404` if there are none.
2. **Anonymous request, public demo enabled** (`insight.demo.public=true`): the business with slug
   `insight.demo.business-slug` (default `fieldstone-apparel`), **read-only**. `X-Business-Id` is
   ignored unless it equals the demo business id (any other value → `401`).
3. **Anonymous, demo disabled** → `401 "Sign in to continue."`.

Path ids (`/api/products/{id}`, `/api/sales/{id}`, `/api/stores/{id}`, `/api/imports/{id}`,
`/api/businesses/{id}/...`) are always checked against the resolved business (already true for
products/sales/stores/imports via their queries) → `404` when they belong elsewhere.

## 4. Roles and permissions (server-side, deny by default)

| Capability | OWNER | ADMIN | VIEWER | Public demo (anonymous) |
|---|---|---|---|---|
| Read dashboard, products, sales, stores, reports, analytics (Cube) | ✓ | ✓ | ✓ | ✓ (demo business only) |
| CSV import: validate, import, list/view batches | ✓ | ✓ | – | – |
| Create stores and products (catalog setup) | ✓ | ✓ | – | – |
| List members | ✓ | ✓ | – | – |
| Add a member (existing account, by email) | any role | VIEWER or ADMIN | – | – |
| Change a member's role | ✓ | – | – | – |
| Remove a member | ✓ (not the last owner) | VIEWERs only | – | – |
| Leave a business (self) | ✓ (not the last owner) | ✓ | ✓ | – |
| Rename business / change time zone | ✓ | – | – | – |

- `401` problem detail when not signed in, `403` when signed in without the role
  (`"You need the ADMIN role for this."`), `403 "The demo is read-only."` for demo writes.
- The `insight.imports.enabled` flag and the `local` profile are **removed**: imports are available
  to OWNER/ADMIN of an authenticated business and never to the public demo.
- Unknown `/api/**` paths: `401` for anonymous, `404` for authenticated users.

## 5. API contracts (JSON, RFC 9457 errors, DTO records)

Public (no session required):

- `GET /api/session` → `200 { authenticated: bool, user: {id, email, displayName} | null,
  memberships: [{businessId, name, slug, role}], demo: { enabled: bool, businessId, name } | null }`.
  Always issues the `XSRF-TOKEN` cookie.
- `POST /api/auth/sign-up {email, password, displayName}` → `201 { user, memberships: [] }` and a
  signed-in session. `400` validation, `409` email taken.
- `POST /api/auth/sign-in {email, password}` → `200 { user, memberships }`; `401 "Invalid email or
  password."` (same message for unknown email and wrong password); `429` when rate-limited.
- `POST /api/auth/password/forgot {email}` → `202` always.
- `POST /api/auth/password/reset {token, newPassword}` → `204`; `400 "This reset link is invalid or
  has expired."`. Signs out all existing sessions of that user.

Authenticated:

- `POST /api/auth/sign-out` → `204` (session invalidated, cookie cleared).
- `POST /api/auth/password/change {currentPassword, newPassword}` → `204`; other sessions signed
  out, the current one kept.
- `GET /api/businesses` → `[{businessId, name, slug, currency, timeZone, role}]`.
- `POST /api/businesses {name, currency (ISO 4217), timeZone (IANA)}` → `201 {businessId, name,
  slug, currency, timeZone, role: "OWNER"}`; slug generated from the name, unique.
- `PATCH /api/businesses/{id} {name?, timeZone?}` → OWNER.
- `GET /api/businesses/{id}/members` → ADMIN+ → `[{userId, email, displayName, role, since}]`.
- `POST /api/businesses/{id}/members {email, role}` → per matrix; `404 "No account with that
  email."` if the user does not exist; `409` if already a member. (Email invitations are later work.)
- `PATCH /api/businesses/{id}/members/{userId} {role}` → OWNER; cannot demote the last owner.
- `DELETE /api/businesses/{id}/members/{userId}` → per matrix; cannot remove the last owner.
- `POST /api/stores {code, name, city?}` → ADMIN+ of the current business → `201` store.
- `POST /api/products {sku, name, category, listPrice}` → ADMIN+ → `201` product.

Business-scoped reads (existing, unchanged shapes, now resolved per §3): `/api/dashboard/**`,
`/api/products/**` (GET), `/api/sales/**`, `/api/stores/**` (GET), `/api/reports/**`,
`/api/imports/**` (ADMIN+), `/api/analytics/**` (new, Cube).

`GET /api/dashboard/context` changes: `features.importsEnabled` is replaced by
`access: { role: "OWNER"|"ADMIN"|"VIEWER"|"DEMO", canImport, canManageCatalog, canManageMembers,
readOnly }` for the resolved business.

## 6. Public demo mode

- `insight.demo.public` (default **false**) turns on anonymous read-only access to the demo
  business. The `demo` profile sets it to `true` (and seeds the data, as today). It is never
  implied by any other setting.
- Every write is refused for demo access (`403 "The demo is read-only."`), including imports.
- Signed-in users see only their own businesses; the demo is what you see when signed out.

## 7. Cube stays private behind the API

- The browser never talks to Cube. The API calls Cube with a short-lived (60 s) HS256 JWT signed
  with `CUBEJS_API_SECRET` whose claims include `businessId` taken **from the resolved membership**
  (`CurrentBusiness`), never from request input.
- Cube's `checkAuth` rejects requests without a valid token (dev mode must not bypass it) and
  `queryRewrite` adds a mandatory `businessId` filter from the token to every query; queries that
  cannot be scoped are rejected. Pre-aggregations include the business dimension; cache/app id is
  per business (`contextToAppId`).
- Compose publishes Cube on `127.0.0.1` only (the API runs on the host); no Playground in the
  default configuration. The secret lives only in `infra/.env` / API environment.
- API: `GET /api/analytics/summary?from&to&storeId` → `{ period, revenue, orders, unitsSold,
  averageOrderValue, source: "cube" }` for the current business; `503 "Analytics is not
  configured."` when `insight.cube.url` is empty (default).
- Configuration keys (API): `insight.cube.url` (e.g. `http://localhost:4000`), `insight.cube.api-secret`
  (from env `CUBEJS_API_SECRET`).

## 8. Ownership (one writer per file)

| Owner | Files |
|---|---|
| **Integrator** | `pom.xml`, `README.md`, this contract, `tenancy/Role.java`, `tenancy/BusinessAccess.java`, `tenancy/CurrentBusiness.java` (interfaces: change only via integrator), merges |
| **backend-auth** agent | everything else under `apps/api/**` except the `analytics` package: `security/**`, `account/**`, `tenancy/**` implementations, `business/**`, `db/migration/V4__create_accounts.sql`, `reporting/ReportingContext.java`, `dashboard/**`, `importing/**`, `store/**`, `product/**` (for the create endpoints), `common/**`, `application*.properties` (incl. removing the `local` profile), all backend tests except `analytics/**` |
| **cube-isolation** agent | `apps/api/src/main/java/.../analytics/**`, `apps/api/src/test/java/.../analytics/**`, `services/analytics/**`, `infra/compose.yaml`, `infra/.env.example`, `docs/analytics.md` |
| **frontend** agent | `apps/web/**` (sole web writer) and `docs/frontend-accounts.md` |

Docs: each agent writes `docs/<area>.md`; only the integrator edits `README.md`.
