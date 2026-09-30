# Accounts, roles and the public demo (API)

How the API authenticates people, decides which business a request may see, and what each role
may do. The binding contract is [accounts-contract.md](accounts-contract.md); this page describes
the implementation in `apps/api` and how to use it locally.

## Sessions and CSRF

- **Server-side sessions** in PostgreSQL (Spring Session JDBC, tables `spring_session` and
  `spring_session_attributes` from Flyway V5). Every API instance reads the same sessions, and they
  survive restarts and deployments. The browser only holds the `SESSION` cookie (`HttpOnly`,
  `SameSite=Lax`; `__Host-SESSION` with the `prod` profile). Idle timeout: 8 hours
  (`server.servlet.session.timeout`); expired sessions are purged every minute.
- **What a session stores**: only the signed-in user's id, email and session version, as plain
  values (`SessionAccountContextRepository`), never a serialized Spring Security object, so a newer
  build can always read sessions written by an older one. Anonymous visitors get no session at all
  (the CSRF token is a cookie). Signing out deletes the session row, ending it on every instance.
- **Session fixation**: every sign-in (and password change) gives the session a new id.
- **CSRF**: the API sets a readable `XSRF-TOKEN` cookie; every `POST`/`PUT`/`PATCH`/`DELETE` must
  send the same value in the `X-XSRF-TOKEN` header, otherwise `403` (problem detail mentioning
  CSRF). Only the header is accepted (no `_csrf` form field). `GET /api/session` always sends the
  cookie; sign-in, password change and sign-out issue a new one, so read the cookie again
  after those calls.
- **Secure cookies**: off by default so plain `http://localhost` works. The `prod` profile turns
  them on and refuses to start without them; see [production.md](production.md).
- **Deny by default**: everything needs a session except `GET /api/session`,
  `POST /api/auth/sign-up|sign-in|password/forgot|password/reset|verify-email`,
  `POST /api/invitations/preview`,
  `GET /actuator/health`, and —
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
- **Sign-up**: `POST /api/auth/sign-up {email, password, displayName}` validates the input (`400`)
  and the per-IP limit (`429`), then **always answers `202` with no body and signs no one in**, so it
  never reveals whether an address has an account:
  - a new address gets an unverified account and a verification link ([below](#email-verification));
  - an existing address is left exactly as it is (password, name, verification, sessions); its
    owner gets a "you already have an account" email with sign-in and reset links (at most 3 an
    hour per address), and the password is hashed anyway so the answer takes as long.

  The new account then signs in with its password, verified or not.
- Rate limits: see [Rate limits](#rate-limits) (sign-in, sign-up, recovery, verification and
  invitations).
- **Forgot password**: `POST /api/auth/password/forgot {email}` always answers `202`. When the
  email has an account, a random 32-byte token (URL-safe base64) is generated, only its SHA-256 is
  stored in `password_reset_tokens`, it expires after 30 minutes, and requesting a new link
  invalidates older ones. The link is `WEB_BASE_URL` + `/reset-password?token=...`
  (property `insight.accounts.web-base-url`, default `http://localhost:5173`).
- **Delivery**: the link is emailed over SMTP ([Email](#email)). It is never logged.
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
| Invite someone by email; list and revoke open invitations | any role | VIEWER or ADMIN invitations | 403 | 401 |
| Change a member's role | ✓ (not demoting the last owner) | 403 | 403 | 401 |
| Remove a member | ✓ (not the last owner) | VIEWERs only | 403 | 401 |
| Leave (remove yourself) | ✓ (not the last owner) | ✓ | ✓ | – |
| Rename business / change time zone | ✓ | 403 | 403 | 401 |

Last-owner protection answers `409 "A business needs at least one owner."`; membership changes
lock the business row, so two concurrent requests cannot both remove "the other" owner.

Store codes and SKUs are 1–50 characters of letters, digits, `.`, `_`, `-` (they are matched
exactly by CSV imports) and unique within a business (`409` otherwise).

## Email verification

An account proves it controls its address before it can change anything:

- Sign-up emails a link to `WEB_BASE_URL` + `/verify-email?token=...`: 32 random bytes, only the
  SHA-256 is stored (`email_verification_tokens`, Flyway V9), single use, expires after 24 hours; a
  new link invalidates older ones.
- `POST /api/auth/verify-email {token}` is public (`204`, or
  `400 "This verification link is invalid or has expired."`). It verifies the account the token was
  sent to, whoever opens it and in whichever browser; it signs no one in.
- `POST /api/auth/verify-email/resend` (signed in) emails a new link; `202`, nothing for an
  account that is already verified, `429` after 3 an hour.
- Accepting an invitation sent to the address, or resetting the password through an emailed link,
  verifies the address too (both prove the same thing).
- **Unverified accounts** can sign in and read, but get `403 "Verify your email address first..."`
  for creating a business and for every business write: imports, stores and products, invitations,
  member changes, renaming (all role-gated actions check it). Leaving a business stays allowed.
  `GET /api/session` reports `user.emailVerified`, and the dashboard context
  `access.emailVerified` (with `readOnly: true` and no `can*` flags until verified).
- Accounts that existed before verification was introduced were marked verified by the migration.

## Invitations

People join a business only through invitations; there is no way to add an account directly (so
nothing tells an inviter whether an address has an account).

| Endpoint | Who | Does |
|---|---|---|
| `POST /api/businesses/{id}/invitations {email, role}` | OWNER (any role), ADMIN (VIEWER, ADMIN) | `201` with the invitation, whether or not the address has an account; emails the link. `409` if the address already belongs to a member |
| `GET /api/businesses/{id}/invitations` | OWNER, ADMIN | Open invitations: `id, email, role, invitedBy, createdAt, expiresAt` |
| `DELETE /api/businesses/{id}/invitations/{invitationId}` | OWNER, ADMIN (not OWNER invitations) | Revokes it: `204` |
| `POST /api/invitations/preview {token}` | anyone with the link | `businessName, role, invitedBy, email, expiresAt` |
| `POST /api/invitations/accept {token}` | signed in | Joins with the invited role: `200` with the business |

- The link is `WEB_BASE_URL` + `/invite?token=...`: 32 random bytes, only the SHA-256 is stored
  (`invitations`, Flyway V7). It works once and expires after 7 days. Inviting the same address
  again replaces the open invitation.
- Only a signed-in account **whose email is the invited address** can accept (`403` otherwise,
  and the link stays usable). An existing member gets `409`.
- The inviter must still be allowed to grant the role when the link is used; if they were removed
  or demoted, the link stops working.
- Unknown, used, revoked, expired and orphaned links all answer the same
  `400 "This invitation is invalid or has expired."`.
- Tokens travel in request bodies, never in API URLs. The web app moves the token out of the address
  bar as soon as `/invite` opens and sends `Referrer-Policy: same-origin`.

## Rate limits

Stored in PostgreSQL (`rate_limit_hits`, Flyway V6), so they apply across every API instance and
survive restarts. Each bucket is a limit name plus the SHA-256 of the email, IP or account; no
address is stored in clear. Windows slide, use the database clock, and concurrent requests take a
per-bucket advisory lock. A limit reached answers `429` with `Retry-After` (seconds).

| Limit | Counts | Max per window |
|---|---|---|
| Sign-in per email / per IP | failed sign-ins and failed password changes | 5 / 20 per 15 min |
| Sign-up per IP | every attempt, successful or not | 10 per hour |
| "Already have an account" notices per address | notices sent (further sign-ups still answer `202`) | 3 per hour |
| Verification emails per account | resends | 3 per hour |
| Verification token use per IP | every attempt | 30 per 15 min |
| Reset request per IP | every request | 10 per hour |
| Reset email per address | emails sent (further requests still answer `202`, nothing is sent) | 3 per hour |
| Reset token use per IP | every attempt | 20 per 15 min |
| Invitations per account | invitations sent | 20 per hour |
| Invitation preview/accept per IP | every attempt | 30 per 15 min |

A successful sign-in or password reset clears the email's failed sign-ins. The client IP comes
from the TCP connection unless it is a trusted proxy ([production.md](production.md#reverse-proxy)).

## Email

Verification, password-reset, "already have an account" and invitation emails are plain text sent
over SMTP (Spring Boot `spring.mail`) through a **persistent outbox** (`mail_outbox`, Flyway V8):

- The email is written in the same transaction as the token it carries, so it exists exactly when
  the token does, and the request never waits for the mail server.
- A worker on every API instance (`MailOutboxWorker`, every 2 s) claims due emails with
  `FOR UPDATE SKIP LOCKED` and a 2-minute lease: instances share the work without sending an email
  twice, and emails claimed by an instance that died are picked up again when the lease ends.
  Queued emails therefore survive restarts, deployments and mail server outages.
- A failed attempt is retried after 30 s, 2 min, 10 min, 30 min and 2 h; after the sixth attempt the
  email is marked `FAILED`. An email whose link expired before it could be sent is marked `EXPIRED`
  instead of sent.
- The body (with the secret link) is erased as soon as the email is sent, failed or expired, and
  finished rows are deleted after 7 days. Logs name the outbox id, the kind of email and the SMTP
  error, never the body, a link or a token. Delivery is at-least-once: an instance that dies right
  after the SMTP server accepted an email can cause it to be sent again.

Settings (`insight.mail.outbox.*`): `enabled`, `poll-interval`, `retry-delays`, `batch-size`,
`lease`, `retention`.

In development the defaults (`localhost:1025`) reach Mailpit from `infra/compose.yaml`; open
http://localhost:8025 to read the emails. Production settings: [production.md](production.md#email).

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

3. Open the verification link from the email in Mailpit (http://localhost:8025), then sign in
   (sign-in rotates the CSRF token: read it again) and create a business (you become its OWNER):

   ```bash
   TOKEN=$(awk '$6=="XSRF-TOKEN"{print $7}' jar.txt)
   curl -c jar.txt -b jar.txt -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
     -d '{"email":"me@example.com","password":"a long passphrase"}' \
     http://localhost:8080/api/auth/sign-in
   TOKEN=$(awk '$6=="XSRF-TOKEN"{print $7}' jar.txt)
   curl -c jar.txt -b jar.txt -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
     -d '{"name":"My Shop","currency":"EUR","timeZone":"Europe/Paris"}' \
     http://localhost:8080/api/businesses
   ```

4. Add stores and products (`POST /api/stores {code, name, city?}`,
   `POST /api/products {sku, name, category, listPrice}`), then import historical sales from CSV
   (`POST /api/imports`, see [csv-import.md](csv-import.md)). With a single business no
   `X-Business-Id` header is needed.

In the web app all of this happens through the sign-up and onboarding screens. Verification,
reset and invitation emails land in Mailpit (http://localhost:8025).

## Tables

Flyway `V4__create_accounts.sql`:

- `users` (`email` unique case-insensitively, `password_hash`, `display_name`, `session_version`,
  `last_sign_in_at`)
- `memberships` (`user_id`, `business_id`) primary key, `role` in `OWNER`/`ADMIN`/`VIEWER`
- `password_reset_tokens` (`token_sha256` unique, `expires_at`, `used_at`)

Later migrations: `V5` `spring_session`, `spring_session_attributes` (sessions); `V6`
`rate_limit_hits`; `V7` `invitations` (`token_sha256` unique, `expires_at`, `accepted_at`,
`revoked_at`; at most one open invitation per business and address); `V8` `mail_outbox`; `V9`
`users.email_verified_at` and `email_verification_tokens`.
