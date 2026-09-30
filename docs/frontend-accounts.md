# Web app: accounts, businesses and roles

How `apps/web` implements the accounts contract (`docs/accounts-contract.md`). The server is
authoritative for every check; the UI only hides what the user can't do.

## Session bootstrap (`App.tsx`, `hooks/useSessionLoader.ts`, `lib/session.ts`)

`GET /api/session` loads first. Then:

| Session | What shows |
|---|---|
| Signed out, `demo.enabled` | The app on the demo business, read-only, with a banner: "You're viewing a read-only demo · Sign in / Create account". No `X-Business-Id` is sent (the server resolves the demo). Account pages redirect to sign-in. |
| Signed out, no demo | `/sign-in?next=<requested path>` |
| Signed in, no membership | Onboarding: `/businesses/new` (and `/account`, to sign out) |
| Signed in | The app on the selected business |

`reload(thenGo)` re-reads the session and navigates in the same tick, so the new address and the
new session render together (used after sign-in, sign-up, sign-out and creating a business).

## API client (`api/client.ts`, `api/account.ts`)

- Every non-GET request sends `X-XSRF-TOKEN` with the `XSRF-TOKEN` cookie value; if the cookie is
  missing it first calls `GET /api/session`, which always issues it.
- Business-scoped calls send `X-Business-Id` for the selected business (`setBusinessScope`, set by
  the workspace in a layout effect before any page request). Session/account calls send none;
  `/api/businesses/{id}/members...` calls send the id in their path.
- `postJson`, `patchJson`, `deleteJson` (202/204 resolve to `undefined`).
- 401 from a call that needs a session dispatches `app:unauthorized`: the app re-reads the
  session and goes to `/sign-in?next=<current path>`. Sign-in, sign-up, sign-out and password
  endpoints are marked `expectUnauthorized` so a wrong password is shown, not redirected.
- 403 without a problem detail reads "You don't have permission to do this."; with one, the
  server's text is shown ("The demo is read-only.", "You need the ADMIN role for this.").
- 429 shows the wait time from `Retry-After`.
- Report CSV exports download through `fetch` (so they carry `X-Business-Id`); the link's `href`
  stays for open-in-new-tab.

## Business selection

The sidebar lists memberships with their role (a menu when there are several) and shows the current
role. The choice is stored in `localStorage` (`insight-studio.businessId`) but is only a hint: it is
used only if it matches a membership from `/api/session`, otherwise the first membership is used.
Switching stores the id and opens `/` without query parameters, so the shared store/date filters
reset (store ids differ per business). The workspace is keyed by business id, so nothing from one
business survives into another. A `404` on the context (membership removed meanwhile) re-reads the
session.

## Role-aware UI

`/api/dashboard/context` `access` replaces `features.importsEnabled`:

- Import nav and `/imports*` pages: `canImport`.
- Settings › Members (`/settings/members`): `canManageMembers`.
- Settings › Catalog (`/settings/catalog`): `canManageCatalog`.
- Otherwise a "You don't have access" page (or "The demo is read-only" with a sign-in button).

Members page controls follow the §4 matrix: OWNER invites any role, ADMIN invites VIEWER/ADMIN;
pending invitations are listed with Revoke (ADMIN can't revoke an OWNER invitation); only OWNER
changes roles; OWNER removes others, ADMIN removes VIEWERs; your own row offers Leave. The last
owner can't be demoted or leave (the select and Leave are hidden). Leaving is also on `/account` for
every membership.

## Invitations

Owners and admins invite by email from Settings › Members; the invitee gets a link to
`/invite?token=…`. The invite page:

- moves the token from the address bar into this tab's `sessionStorage` at once (it never stays in
  history or bookmarks) and previews the invitation (`POST /api/invitations/preview`);
- signed out: offers "Sign in to accept" and "Create an account", both returning to `/invite`
  through `?next=` (sign-up with a pending invitation goes back to it instead of onboarding);
- signed in with the invited address: "Join {business}" accepts, selects the joined business and
  opens its dashboard;
- signed in with another address: explains which address the invitation is for and offers to sign out;
- used, revoked or expired links show one "invalid or has expired" message.

`index.html` sets `<meta name="referrer" content="same-origin">`, so reset and invitation URLs are
never sent to other sites.

## Routes

`/sign-in`, `/sign-up`, `/forgot-password`, `/reset-password?token=…`, `/invite?token=…`, `/account`,
`/businesses/new`, `/settings/members`, `/settings/catalog`, plus the existing pages. `?next=` is only
followed for same-site paths (never `//host` or absolute URLs).

## Forms

Labels, `autocomplete` (`email`, `current-password`, `new-password`, `name`, `organization`), inline
validation (password at least 12 characters and at most 72 UTF-8 bytes, not the email;
confirmations must match), server errors
in an alert, busy/disabled states and success confirmations. Blurring an empty field doesn't flag it
(errors for empty required fields appear on submit), so the layout doesn't shift under the pointer.
Forgot-password always ends with the neutral "If an account exists for …, we sent a link"; only a
network error, rate limit or server error is shown instead.

## Assumptions to check against the real API

1. `GET /api/dashboard/context` returns `access` as described; `business` has no id (the selected
   id comes from the session).
2. `POST /api/stores` returns `{id, code, name, city}` and `POST /api/products` returns
   `{id, sku, name, category, listPrice}` (used for "View store/product" links; missing ids just hide
   the link).
3. `POST /api/businesses/{id}/invitations` returns the invitation (`{id, email, role, invitedBy,
   createdAt, expiresAt}`); `PATCH .../members/{userId}` returns the member or nothing.
4. Leaving a business is `DELETE /api/businesses/{id}/members/{myUserId}` with `X-Business-Id: {id}`.
5. A wrong current password on `POST /api/auth/password/change` is a `400` with a detail message (a
   `401` is shown as an error too, without redirecting).
6. The CSRF cookie is refreshed by the server after sign-in/sign-out (Spring's SPA setup); the client
   always reads the cookie at request time.
7. `/api/reports/*.csv` accepts `X-Business-Id` and sets `Content-Disposition` with a filename.
