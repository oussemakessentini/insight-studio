# Web app: business settings, activity, exports and deletion

How `apps/web` implements §5 of `docs/account-management-contract.md`. The server is authoritative
for every rule; the UI hides or disables what the user can't do.

## Routes and navigation

| Route | Who | Page |
|---|---|---|
| `/settings/business` | OWNER (edit), ADMIN (read-only) | `pages/BusinessSettingsPage.tsx` |
| `/settings/activity` | OWNER, ADMIN | `pages/ActivityPage.tsx` |
| `/account` | signed in | `pages/AccountPage.tsx` + `components/AccountDataPanels.tsx` |

Sidebar › Settings gains **Business** and **Activity** (`lib/settingsAccess.ts#isAdminOrOwner`: role
OWNER or ADMIN, verified or not). Viewers don't see them, and a direct URL shows the existing
"You don't have access to this page" state (or "Verify your email to continue" for an unverified
account, as for the other settings pages). Both routes require an account (anonymous → sign-in).

## API client (`api/accountManagement.ts`)

`businessSettingsApi` (`get`, `update`, `timeZonePreview`, `exportData`, `deletionPreview`, `delete`),
`auditApi.list`, `accountDataApi` (`exportData`, `deletionPreview`, `delete`). Business calls send the
path's id as `X-Business-Id`; account calls send none. `deleteJson` now accepts a JSON `body` and
`downloadFile` accepts request options (business id), both in `api/client.ts`.

## Settings › Business

- Loads `GET /api/businesses/{id}/settings`.
- **ADMIN**: a read-only list (name, time zone, currency, created, your role); subtitle "Only owners
  can change these settings."
- **OWNER**: form "Business settings" with **Business name**, a **Time zone** fieldset (**Search time
  zones** filters the **Business time zone** select, built from `Intl.supportedValuesOf('timeZone')`
  plus the saved zone and UTC), and **Currency** (disabled unless `currencyChangeAllowed`; the hint is
  `currencyLockedReason`). Only changed fields are sent (`PATCH`). Unverified owners see the fields
  disabled with "Verify your email address to change these settings."
- Picking another zone loads `time-zone-preview` and shows the impact (`.tz-impact`): what never
  changes (no stored row, totals over all sales), what does (days/weeks/months re-bucketed, fixed
  dates read in the new zone, rolling ranges follow today in the new zone), the counts of sales
  moving day and month, and the table of changed months (`.tz-impact-table`: Month, Revenue now,
  Revenue after, Orders now, Orders after). **Save changes** stays disabled until the preview loaded.
- After saving: success notice "Saved: …", the settings reload, the workspace context is refreshed
  (`refreshContext`, so reports/dashboards use the new zone and currency) and the session reloads
  (business name in the switcher).

### Danger zone (OWNER, `.danger-zone`)

- **Export business data** downloads the ZIP through `fetch` (name from `Content-Disposition`).
  Busy label "Preparing export…"; success "Your export has been downloaded."; a `429` reads
  "Too many attempts. Try again in N minutes." (from `Retry-After`).
- **Delete business** opens the dialog "Delete {name}?": the deletion preview (non-zero counts in
  `.deletion-counts`, other members who lose access), **Your password**, **Type the business name to
  confirm**. The **Delete business** submit stays disabled until the typed name equals the name
  (trimmed, case-sensitive) and a password is entered. Errors (e.g. "Your password is incorrect.")
  are announced (`role="alert"`). On success the session reloads and the app goes to `/` of another
  business, or to `/businesses/new` ("Create a business" / onboarding) when none is left.

## Settings › Activity

- `GET /api/businesses/{id}/audit?limit=50[&category][&before]`, newest first.
- Filter **Show** (All activity, Business settings, Members, Imports, Charts, Dashboards), kept in the
  URL as `?category=` (re-read on back/forward; unknown values mean all).
- Each entry (`.activity-item`): a sentence from `lib/audit.ts#auditSentence` (every action of the
  contract table; unknown actions fall back to `Actor: action.`), the actor (`.activity-actor`,
  italic `.is-deleted` for "Deleted account", "Someone" when null), relative time (`<time>`, refreshed
  every minute) and the absolute time in the business time zone.
- **Load more** fetches `before=nextBefore` and appends; it disappears when `nextBefore` is null
  ("That's everything."). Empty state, error state with **Try again**, failed "Load more" shown above
  the button.

## Account page

- **Your data** › **Download your data** (JSON export; busy "Preparing download…"; 429 as above).
- **Delete account** › dialog "Delete your account?" loading the deletion preview:
  - `blockingBusinesses` non-empty: an alert explains last-owner protection, each business has a
    link "Manage members of {name}" (switches to that business and opens `/settings/members`), and
    **Delete account** is disabled.
  - Otherwise: memberships you leave, authored content that stays, "N open invitations you sent will
    be revoked." (when `openInvitationsSent` > 0), **Your password**, **Type your
    email to confirm** (case ignored); **Delete account** is enabled only when both are filled and the
    email matches. On success the session reloads and the app opens `/sign-in?accountDeleted=1`,
    which shows "Your account has been deleted and you have been signed out. …".

## Dialogs and accessibility

All dialogs use `components/Dialog.tsx` (native `<dialog>` with `showModal()`: focus trapped, page
inert, focus returned to the opener; Escape cancels unless a request is in flight). Inputs are
labelled `TextField`/`SelectField`s; notices sit in `aria-live` regions and errors use
`role="alert"`. Layout targets 1440px and 390px without page overflow (not yet checked in a browser): the month table scrolls inside its box, the
danger-zone buttons go full width and the activity filter stretches on small screens.

## Tests

`npm test` runs `node --test tests/*.test.ts` (Node ≥ 22.18 strips the types; no dependency added):
`tests/audit.test.ts` (one sentence per action, deleted/unknown actors, missing details, category
parsing, relative time) and `tests/accountSettings.test.ts` (typed confirmations, time-zone search,
changed fields, name rules).

## Known limitations

- Member events carry only roles (contract §2), so "changed the role of a member" doesn't name the
  member unless the server adds `displayName`/`name`/`email` to `details` (used when present).
- After deleting a business, the next page (the other business's overview, or "Create a business")
  shows a dismissible confirmation banner (`.flash-banner`, `role="status"`): "“{deleted}” was deleted.
  You're now working in “{name}”." (`lib/flash.ts`, in memory: it disappears on dismiss, on the next
  page change or on a full reload).
- The settings pages were checked against the contract shapes, not a running backend.
