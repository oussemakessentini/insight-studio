# Accounts, roles and the public demo (API)

How the API authenticates people, decides which business a request may see, and what each role
may do. The binding contract is [accounts-contract.md](accounts-contract.md); this page describes
the implementation in `apps/api` and how to use it locally.

## Sessions and CSRF

- **Server-side sessions** (Spring Security). Signing in stores the user in the HTTP session; the
  browser only holds the `JSESSIONID` cookie (`HttpOnly`, `SameSite=Lax`). Sessions live in memory:
  a single API instance, and every session is lost when the API restarts. Idle timeout: 8 hours
  (`server.servlet.session.timeout`).
- **Session fixation**: every sign-in (and password change) gives the session a new id.
- **CSRF**: the API sets a readable `XSRF-TOKEN` cookie; every `POST`/`PUT`/`PATCH`/`DELETE` must
  send the same value in the `X-XSRF-TOKEN` header, otherwise `403` (problem detail mentioning
  CSRF). Only the header is accepted (no `_csrf` form field). `GET /api/session` always sends the
  cookie; sign-in, sign-up, password change and sign-out issue a new one, so read the cookie again
  after those calls.
- **Secure cookies**: off by default so plain `http://localhost` works. Set `COOKIE_SECURE=true`
  (property `insight.security.cookie-secure`) wherever the app is served over HTTPS.
- **Deny by default**: everything needs a session except `GET /api/session`,
  `POST /api/auth/sign-up|sign-in|password/forgot|password/reset`, `GET /actuator/health`, and —
  only when the public demo is enabled — `GET` on the business read endpoints (`/api/dashboard/**`,
  `/api/products/**`, `/api/sales/**`, `/api/stores/**`, `/api/reports/**`, `/api/analytics/**`).
  Not signed in: `401 "Sign in to continue."`. Signed in without the role: `403`. Unknown `/api/**`
  paths: `401` when signed out, `404` when signed in. All errors are `application/problem+json`.
- **Stale sessions**: each user has a `session_version`. A password change or reset increments it;
  a session carrying an older version is dropped on its next request (it behaves as signed out).

## Passwords, sign-in limits and recovery

- Passwords are hashed with bcrypt through Spring Security's `DelegatingPasswordEncoder`
  (`{bcrypt}...` in `users.password_hash`).
- Policy: 12–128 characters, not equal to the email, **and at most 72 bytes in UTF-8** (bcrypt
  ignores anything after 72 bytes and Spring Security refuses longer input, so longer passwords are
  rejected rather than silently truncated; 72 plain ASCII characters).
- Emails are trimmed and compared case-insensitively (unique index on `lower(email)`).
- Sign-in answers `401 "Invalid email or password."` for an unknown email and for a wrong
  password alike, and does the same bcrypt work in both cases.
- Brute force: after 5 failed sign-ins for one email, or 20 from one client IP, within 15 minutes,
  further attempts get `429` with `Retry-After` (seconds). Wrong current passwords on
  `password/change` count too. The counters are in memory, per instance, and reset on restart. The
  IP is the TCP peer address: behind a reverse proxy, configure Spring Boot's forwarded-header
  support, or every user shares the proxy's address.
- **Forgot password**: `POST /api/auth/password/forgot {email}` always answers `202`. When the
  email has an account, a random 32-byte token (URL-safe base64) is generated, only its SHA-256 is
  stored in `password_reset_tokens`, it expires after 30 minutes, and requesting a new link
  invalidates older ones. The link is `insight.accounts.reset-link-base` + `?token=...`
  (default `http://localhost:5173/reset-password?token=...`, override with `RESET_LINK_BASE`).
- **Delivery**: there is no mail server yet. The default `PasswordResetNotifier` logs the link at
  INFO on the `insight.password-reset` logger — the link is a secret, so this is for local
  development only. Provide another `PasswordResetNotifier` bean (e.g. a mail sender) before other
  people use the API.
- **Reset**: `POST /api/auth/password/reset {token, newPassword}` → `204`, or
  `400 "This reset link is invalid or has expired."`. A token works once; a password that fails the
  policy does not use it up. A reset signs out every session of the user.
- **Change**: `POST /api/auth/password/change {currentPassword, newPassword}` → `204`; the current
  session stays signed in (with a new session id), every other session is signed out.

## Which business a request sees

Every business-scoped endpoint resolves exactly one business through `tenancy.CurrentBusiness`
(`MembershipCurrentBusiness`) and filters every query by it:

1. **Signed in**: send `X-Business-Id: <id>` to choose among your memberships. It is only a
   selector; the server loads your membership for that id and answers `404 "Business not found."`
   if there is none (other businesses' ids are never confirmed). Without the header: your only
   business, `400 "Select a business (X-Business-Id)."` if you have several, `404` if you have none.
2. **Signed out, public demo enabled**: the demo business, read-only. A header naming any other
   business → `401`.
3. **Signed out otherwise**: `401`.

Ids in paths (products, sales, stores, import batches) are then looked up inside the resolved
business, so another business's id is a `404`. `/api/businesses/{id}/...` endpoints use the path
id as the selector (the header is ignored there) and answer `404` unless you are a member.

`GET /api/dashboard/context` returns `access: {role, canImport, canManageCatalog,
canManageMembers, readOnly}` (`role` is `DEMO` for the public demo) so the UI can hide what the
server would refuse anyway.

## Roles

| Capability | OWNER | ADMIN | VIEWER | Public demo |
|---|---|---|---|---|
| Read dashboard, products, sales, stores, reports, analytics | ✓ | ✓ | ✓ | ✓ (demo business only) |
| CSV import, import history | ✓ | ✓ | 403 | 401 |
| Create stores (`POST /api/stores`) and products (`POST /api/products`) | ✓ | ✓ | 403 | 401 |
| List members | ✓ | ✓ | 403 | 401 |
| Add a member (existing account, by email) | any role | VIEWER or ADMIN | 403 | 401 |
| Change a member's role | ✓ (not demoting the last owner) | 403 | 403 | 401 |
| Remove a member | ✓ (not the last owner) | VIEWERs only | 403 | 401 |
| Leave (remove yourself) | ✓ (not the last owner) | ✓ | ✓ | – |
| Rename business / change time zone | ✓ | 403 | 403 | 401 |

Last-owner protection answers `409 "A business needs at least one owner."`; membership changes
lock the business row, so two concurrent requests cannot both remove "the other" owner.

Store codes and SKUs are 1–50 characters of letters, digits, `.`, `_`, `-` (they are matched
exactly by CSV imports) and unique within a business (`409` otherwise).

## Public demo

- `insight.demo.public` (default `false`) enables anonymous read-only access to the business whose
  slug is `insight.demo.business-slug` (default `fieldstone-apparel`). The `demo` profile turns it
  on and seeds that business.
- Signed-out visitors can only `GET` the read endpoints above; every write, imports and the
  business/member endpoints answer `401`.
- Safety nets: a business that has members is never served as the public demo, even if its slug
  matches, and new businesses never receive the configured demo slug (it gets a `-2` suffix).
- Signed-in users see only their own businesses, never the demo.

## Local setup: first account and business

1. Start the API as usual (see README). With the `demo` profile you can browse the demo signed out.
2. Get a CSRF token and sign up (curl keeps the cookies in `jar.txt`):

   ```bash
   curl -c jar.txt -b jar.txt http://localhost:8080/api/session
   TOKEN=$(awk '$6=="XSRF-TOKEN"{print $7}' jar.txt)
   curl -c jar.txt -b jar.txt -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
     -d '{"email":"me@example.com","password":"a long passphrase","displayName":"Me"}' \
     http://localhost:8080/api/auth/sign-up
   ```

3. Sign-up rotates the CSRF token: read it again, then create a business (you become its OWNER):

   ```bash
   TOKEN=$(awk '$6=="XSRF-TOKEN"{print $7}' jar.txt)
   curl -c jar.txt -b jar.txt -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
     -d '{"name":"My Shop","currency":"EUR","timeZone":"Europe/Paris"}' \
     http://localhost:8080/api/businesses
   ```

4. Add stores and products (`POST /api/stores {code, name, city?}`,
   `POST /api/products {sku, name, category, listPrice}`), then import historical sales from CSV
   (`POST /api/imports`, see [csv-import.md](csv-import.md)). With a single business no
   `X-Business-Id` header is needed.

In the web app all of this happens through the sign-up and onboarding screens; forgotten-password
links appear in the API log (logger `insight.password-reset`).

## Tables (Flyway `V4__create_accounts.sql`)

- `users` (`email` unique case-insensitively, `password_hash`, `display_name`, `session_version`,
  `last_sign_in_at`)
- `memberships` (`user_id`, `business_id`) primary key, `role` in `OWNER`/`ADMIN`/`VIEWER`
- `password_reset_tokens` (`token_sha256` unique, `expires_at`, `used_at`)
