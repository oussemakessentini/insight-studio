# Web app: custom dashboards

How `apps/web` implements `docs/dashboards-contract.md` (§6). The server is authoritative for every
check; the editor mirrors the layout rules of §2 so it never offers a layout the server would refuse,
and shows the server's message whenever it refuses anyway.

## Routes and navigation

| Path | Route | Page |
|---|---|---|
| `/` | `dashboard` | the existing overview, unchanged; its sidebar item is now **Overview** |
| `/dashboards` | `dashboards` | `pages/DashboardsPage.tsx`: the list |
| `/dashboards/{id}` | `dashboardView` | `pages/DashboardViewPage.tsx` (keyed by id); `?revision=n` shows revision *n* read-only |
| `/dashboards/{id}/edit` | `dashboardEdit` | `pages/DashboardEditPage.tsx`; `?layout=mobile` opens the mobile tab |

A new sidebar item **Dashboards** (second, `LayoutIcon`) stays active on all three routes. View and
edit are separate URLs, so refresh and back/forward keep the mode, the revision and the layout tab.

## API client

- `api/dashboards.ts`: `DashboardLayout` (`schemaVersion`, `widgets`, `desktop`, `mobile`),
  `LayoutItem`, `Dashboard` (with `widgets[].chart` or `null` + `missing`), `DashboardSummary`,
  `DashboardRevisionSummary`, `DashboardRef`, and `dashboardsApi`
  `list / get(id, revision?) / create(name, layout?) / update(id, name, layout, expectedRevision) /
  duplicate(id, name?) / remove / revisions`. `dashboardConflict(error)` reads a stale save's 409
  (`currentRevision`, `updatedBy`, `updatedAt`); a 409 without `currentRevision` (a taken name, the
  50-dashboard limit) is shown as the server's message.
- `api/charts.ts`: `chartsApi.dashboards(id)` → `GET /api/charts/{id}/dashboards`.
- `api/client.ts`: `ApiError.problem` keeps the whole problem-detail body (null when there is none).

## Layout rules (`lib/dashboardLayout.ts`)

Pure functions over the contract's units: 12 desktop / 4 mobile columns, heights 2–12, `y + h ≤ 200`,
at most 24 widgets, 80 px rows on screen, desktop layout from a 1024 px wide viewport.

- `placeItem` puts one card at a rectangle. **The card takes that spot; every card it would cover
  moves down**, just below the card covering it, in cascade (the moved card never moves). Out of
  bounds, or a push past row 200, is refused with a reason. Nothing floats back up, so gaps stay
  where the user leaves them.
- `applyAction` is one step of move left/right/up/down or wider/narrower/taller/shorter through
  `placeItem`; `actionForKey` maps arrows / Shift+arrows.
- `addWidget` gives the new widget an id (`w-` + 6 hex digits) and places it at the first free spot
  (top to bottom, left to right) of **both** grids: 6×4 on desktop, 4×4 on mobile.
- `withoutMissingWidgets`, `removeWidgets`, `layoutProblems` (every §2 check as messages, run before
  saving), `sameLayout` (for unsaved changes).

## View (`/dashboards/{id}`)

- Header: name, "8 charts · Desktop layout · Revision 3 · Saved … by …", **Refresh** (reloads the
  dashboard, so chart titles are live, and reruns every widget), **Edit** (verified owners/admins).
- `components/dashboards/DashboardGrid.tsx` places the desktop grid at ≥ 1024 px (`useMediaQuery`),
  the mobile grid below; items render in reading order so tab order follows the screen.
- `components/dashboards/WidgetCard.tsx`: a card with the chart's current title (a link to the
  chart), a Chart/Table toggle, and its own state: skeleton, `ChartBody` (the existing renderer, given
  the card's measured height so plots, captions and notes fit; long tables, ranked bars and pie
  legends scroll inside the card), "No sales match…" when empty, the server's error with **Try
  again**, and for a 400 the existing "Edit the chart" hint. KPI tiles are smaller inside cards.
- A widget whose chart was deleted (`chart: null`, or a 404 from its data) shows **This chart was
  deleted**; a note above the grid counts them and links admins to the editor.
- Data requests go through `lib/requestQueue.ts` `chartDataQueue`: at most **3** in flight across
  all widgets, in order. A 429 frees the slot, waits `Retry-After` (1 s if absent) and goes back to
  the front of the line (up to 20 times, then its error shows). Aborted requests (leaving the page,
  Refresh) leave the queue at once.
- `?revision=n`: a note "You're looking at revision n saved … It is read-only, and its charts show
  their current settings and today's data; the dashboard now uses revision m. See the current
  version"; no Edit. A missing revision says so. **Revisions** lists every revision (name, chart
  count, when, who), the current one tagged.

## Editor (`/dashboards/{id}/edit`)

- Name field, **Add chart** (picker dialog of saved charts with a search box; charts already on the
  dashboard are marked but can be added again), **Desktop / Mobile** tabs (ARIA tabs, arrow keys
  switch), **Cancel** (back to the view) and **Save**.
- `components/dashboards/LayoutEditor.tsx` draws the grid with faint column guides. Each card has:
  a **handle** (drag to move; focus it and press arrows to move, Shift + arrows to resize), a
  **corner** (drag to resize), **Remove**, the position ("6 × 4 · columns 1 to 6, rows 1 to 4") and
  buttons Move left/up/down/right, Narrower/Wider/Shorter/Taller. Buttons that can't act are
  `aria-disabled` (not `disabled`, so focus stays) and say why when pressed.
- Dragging uses pointer events with pointer capture (mouse, pen and touch; `touch-action: none` on
  the handles), snaps to grid units, previews the pushed layout live, scrolls near the window edge,
  and Escape (on the handle) cancels. No drag-and-drop dependency.
- Every change is announced in a visible `role="status"` line, e.g. "Revenue by store moved to
  columns 7 to 12, rows 7 to 10. 3 other charts moved down." or "…: It is already at the right
  edge." Focus stays on the control that was used even when the card moves in reading order.
- The mobile tab is arranged at phone width (max 440 px). When a grid's columns would be narrower
  than 48 px (the desktop tab on a phone), the editor shows a **map** of the grid with numbered
  cards and lists the cards below; the buttons and keys are the way to arrange it there.
- Deleted charts: a note explains they must be removed before saving, with **Remove deleted
  charts**; Save is refused locally (the note turns into an alert) until they are gone.
- Save: client checks (name 1–120, no control characters; `layoutProblems`), then `PUT` with
  `expectedRevision` = the revision the edit started from, then the view. A taken name shows next to
  the field; 400 `errors` are listed, naming the widget when the path points at one.
- **409 stale save**: a dialog "Someone else saved this dashboard": who, when, current revision and
  the one started from. **Reload latest** discards local changes and loads the current revision;
  **Keep editing** keeps them and moves `expectedRevision` to the current revision, so the next Save
  replaces the other version (the dialog says so).

## Leaving with unsaved changes

- `lib/router.ts` `blockNavigation(confirm)`: while set, `navigate()` (every `Link`, the sidebar,
  redirects) calls `confirm(proceed)` instead; `navigate(href, {force: true})` skips it (after a
  save).
- Back/forward: history entries now carry their index (`history.state.idx`; `navigate`,
  `updateQuery` and the auth pages keep it). The router's own `popstate` listener runs before
  React's: when blocked it stops the event, goes back by the same distance (that second `popstate`
  is swallowed) and asks; confirming replays the jump. An entry without an index (made outside the
  router) is handled by pushing the editor's URL back on top.
- `hooks/useUnsavedChanges(dirty)` wires this to a **Leave without saving?** dialog (Stay and keep
  editing / Leave without saving) and adds `beforeunload` for refresh and closing the tab.

## Charts page

Deleting a chart (chart page and charts list) first reads `GET /api/charts/{id}/dashboards`
(`components/charts/ChartUsage.tsx`): "It is on 2 dashboards: Sales overview, Store managers. Each
will show 'This chart was deleted' in its place until an owner or admin removes it." (links), or "No
dashboard shows it."; the list's inline confirmation shows "On 2 dashboards".

## Permissions

`dashboardPermissions(access)` follows `chartPermissions`:

| Who | Dashboards |
|---|---|
| Public demo | every `/dashboards*` page shows "Dashboards need an account" with Sign in (the API answers 401) |
| VIEWER | list, view, Refresh, revisions; `/edit` shows "You don't have access" |
| OWNER / ADMIN, unverified | as VIEWER, plus a note to verify (with "Send a new link"); `/edit` shows "Verify your email to continue" |
| OWNER / ADMIN, verified | everything: New (name dialog, then the editor), Rename (dialog), Duplicate, Delete (inline confirmation), Edit |

Renaming from the list is a save: it reads the current dashboard and `PUT`s it with the new name.
Since a layout with deleted charts can't be saved, the rename dialog says it also removes their
placeholders when there are any.

## For the integrator to verify against the real backend

1. The stale-save 409 body carries `currentRevision` (number), `updatedBy` (string or null),
   `updatedAt` (ISO instant) at the top level of the problem detail; a 409 without
   `currentRevision` is treated as "name taken / limit" and its `detail` is shown.
2. `GET /api/dashboards/{id}?revision=n` answers a `Dashboard` whose `name`, `layout` and
   `revision` are revision n's, with `widgets[].chart` live; 404 for a missing revision. The header
   of an older revision takes its date and author from `GET …/revisions`.
3. `GET …/revisions` is newest first; its first entry is taken as the current revision.
4. Widget ids generated by the UI match `^[A-Za-z0-9_-]{1,40}$` (`w-1a2b3c`).
5. `POST /api/dashboards` with `{name}` only creates an empty layout; `POST …/duplicate` accepts
   `{}`.
6. 429 from `GET /api/charts/{id}/data` carries `Retry-After` in seconds (1 assumed if missing).
7. `GET /api/charts/{id}/dashboards` answers `[{id, name}]` (empty list when unused).

## Checked with mocked contract responses

`npm run lint`, `npx tsc -b`, `npm run build`, and a puppeteer run against Vite with every endpoint
mocked: list (desktop 1440 / mobile 390, new dashboard with a taken name), view with loading, data,
empty, error with retry, deleted and 429-then-success widgets (never more than 3 chart requests in
flight, also after Refresh; the 429 retried after 1 s), revision view, viewer/unverified/demo, the
editor (drag move with push-down, corner resize, keyboard moves/resizes with announcements and focus
kept, buttons, blocked steps, deleted chart blocking save, picker, 409 Keep editing then save with
the new expected revision, Reload latest), `?layout=mobile` after refresh, the phone editor (map
and list on the desktop tab, grid on the mobile tab), unsaved-changes prompts on a sidebar link, on
browser Back (Stay and Leave) and on refresh, and the chart delete warnings. No page overflows
horizontally at 1440 or 390.
