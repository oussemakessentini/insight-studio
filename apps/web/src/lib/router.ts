import { useSyncExternalStore } from 'react'

// A minimal History API router: a handful of routes doesn't justify a routing dependency.

export type Route =
  | { name: 'dashboard' }
  | { name: 'products' }
  | { name: 'product'; productId: number }
  | { name: 'sales' }
  | { name: 'sale'; saleId: number }
  | { name: 'stores' }
  | { name: 'store'; storeId: number }
  | { name: 'reports' }
  | { name: 'savedReports' }
  | { name: 'savedReport'; savedReportId: number }
  | { name: 'charts' }
  | { name: 'chartNew' }
  | { name: 'chart'; chartId: number }
  | { name: 'chartEdit'; chartId: number }
  | { name: 'dashboards' }
  | { name: 'dashboardView'; dashboardId: number }
  | { name: 'dashboardEdit'; dashboardId: number }
  | { name: 'imports' }
  | { name: 'import'; importId: number }
  | { name: 'signIn' }
  | { name: 'signUp' }
  | { name: 'forgotPassword' }
  | { name: 'resetPassword' }
  | { name: 'invite' }
  | { name: 'verifyEmail' }
  | { name: 'account' }
  | { name: 'newBusiness' }
  | { name: 'members' }
  | { name: 'catalog' }
  | { name: 'notFound' }

/** Pages for signing in and recovering an account; they don't need a session or a business. */
export type AuthRouteName = 'signIn' | 'signUp' | 'forgotPassword' | 'resetPassword'

export function isAuthRoute(route: Route): route is Route & { name: AuthRouteName } {
  return route.name === 'signIn' || route.name === 'signUp' || route.name === 'forgotPassword' || route.name === 'resetPassword'
}

/** Pages about the signed-in user or business administration; never shown to anonymous visitors. */
export function requiresAccount(route: Route): boolean {
  return route.name === 'account' || route.name === 'newBusiness' || route.name === 'members' || route.name === 'catalog'
}

const NAVIGATE_EVENT = 'app:navigate'

function subscribe(onChange: () => void) {
  window.addEventListener('popstate', onChange)
  window.addEventListener(NAVIGATE_EVENT, onChange)
  return () => {
    window.removeEventListener('popstate', onChange)
    window.removeEventListener(NAVIGATE_EVENT, onChange)
  }
}

/** Current pathname; re-renders on link navigation and browser back/forward (not on query-only updates). */
export function usePathname(): string {
  return useSyncExternalStore(subscribe, () => window.location.pathname)
}

/** Current query string; re-renders on any navigation, including links that only change the query. */
export function useSearch(): string {
  return useSyncExternalStore(subscribe, () => window.location.search)
}

// ---- Leaving a page with unsaved changes -------------------------------------------------------

/** Asks whether to leave; calls `proceed` to go ahead, or nothing to stay. */
export type NavigationBlocker = (proceed: () => void) => void

let blocker: NavigationBlocker | null = null

/**
 * While set, in-app navigation (links, `navigate`, browser back/forward) asks `confirm` first.
 * Returns the function that removes it. Refresh and closing the tab are `beforeunload`'s job.
 */
export function blockNavigation(confirm: NavigationBlocker): () => void {
  blocker = confirm
  return () => {
    if (blocker === confirm) blocker = null
  }
}

// Every history entry this app creates carries its position (`idx`), so a blocked back/forward
// can be undone by going the same distance the other way.
function entryIndex(): number | null {
  const state: unknown = window.history.state
  return state && typeof state === 'object' && typeof (state as { idx?: unknown }).idx === 'number' ? (state as { idx: number }).idx : null
}

if (entryIndex() === null) window.history.replaceState({ ...(window.history.state ?? {}), idx: 0 }, '')
let currentIndex = entryIndex() ?? 0
let currentUrl = currentLocation()
/** The popstate of undoing a blocked back/forward: not a navigation. */
let undoing = false
/** The popstate of a back/forward the user confirmed: let it through. */
let confirmed = false

// Counts back/forward navigations. Registered at import time so it runs before React's own
// popstate subscribers read the snapshot, and can hide a blocked navigation from them.
let historyVersion = 0
window.addEventListener('popstate', (event) => {
  if (undoing) {
    undoing = false
    event.stopImmediatePropagation()
    return
  }
  const target = entryIndex()
  const targetUrl = currentLocation()
  if (blocker && !confirmed && target !== currentIndex) {
    // Back on the page being left, then ask; React never sees the other page.
    event.stopImmediatePropagation()
    const ask = blocker
    if (target === null) {
      // An entry without a position (made outside this router): put this page back on top.
      window.history.pushState({ idx: currentIndex }, '', currentUrl)
      ask(() => navigate(targetUrl, { force: true }))
    } else {
      const delta = target - currentIndex
      undoing = true
      window.history.go(-delta)
      ask(() => {
        confirmed = true
        window.history.go(delta)
      })
    }
    return
  }
  confirmed = false
  if (target !== null) currentIndex = target
  currentUrl = targetUrl
  historyVersion += 1
})

function subscribeToPopState(onChange: () => void) {
  window.addEventListener('popstate', onChange)
  return () => window.removeEventListener('popstate', onChange)
}

/**
 * Changes on every browser back/forward navigation. State that mirrors the URL can use it as a
 * reset key to re-read the URL of the restored history entry during the same render.
 */
export function useHistoryVersion(): number {
  return useSyncExternalStore(subscribeToPopState, () => historyVersion)
}

export function matchRoute(pathname: string): Route {
  const path = pathname.replace(/\/+$/, '') || '/'
  if (path === '/') return { name: 'dashboard' }
  if (path === '/products') return { name: 'products' }
  const product = /^\/products\/(\d+)$/.exec(path)
  if (product) return { name: 'product', productId: Number(product[1]) }
  if (path === '/sales') return { name: 'sales' }
  const sale = /^\/sales\/(\d+)$/.exec(path)
  if (sale) return { name: 'sale', saleId: Number(sale[1]) }
  if (path === '/stores') return { name: 'stores' }
  const store = /^\/stores\/(\d+)$/.exec(path)
  if (store) return { name: 'store', storeId: Number(store[1]) }
  if (path === '/reports') return { name: 'reports' }
  if (path === '/reports/saved') return { name: 'savedReports' }
  const savedReport = /^\/reports\/saved\/(\d+)$/.exec(path)
  if (savedReport) return { name: 'savedReport', savedReportId: Number(savedReport[1]) }
  if (path === '/charts') return { name: 'charts' }
  if (path === '/charts/new') return { name: 'chartNew' }
  const chart = /^\/charts\/(\d+)$/.exec(path)
  if (chart) return { name: 'chart', chartId: Number(chart[1]) }
  const chartEdit = /^\/charts\/(\d+)\/edit$/.exec(path)
  if (chartEdit) return { name: 'chartEdit', chartId: Number(chartEdit[1]) }
  if (path === '/dashboards') return { name: 'dashboards' }
  const dashboard = /^\/dashboards\/(\d+)$/.exec(path)
  if (dashboard) return { name: 'dashboardView', dashboardId: Number(dashboard[1]) }
  const dashboardEdit = /^\/dashboards\/(\d+)\/edit$/.exec(path)
  if (dashboardEdit) return { name: 'dashboardEdit', dashboardId: Number(dashboardEdit[1]) }
  if (path === '/imports') return { name: 'imports' }
  const batch = /^\/imports\/(\d+)$/.exec(path)
  if (batch) return { name: 'import', importId: Number(batch[1]) }
  if (path === '/sign-in') return { name: 'signIn' }
  if (path === '/sign-up') return { name: 'signUp' }
  if (path === '/forgot-password') return { name: 'forgotPassword' }
  if (path === '/reset-password') return { name: 'resetPassword' }
  if (path === '/invite') return { name: 'invite' }
  if (path === '/verify-email') return { name: 'verifyEmail' }
  if (path === '/account') return { name: 'account' }
  if (path === '/businesses/new') return { name: 'newBusiness' }
  if (path === '/settings/members') return { name: 'members' }
  if (path === '/settings/catalog') return { name: 'catalog' }
  return { name: 'notFound' }
}

/**
 * Navigates client-side; `replace` swaps the current history entry (for redirects). While a page
 * blocks navigation (unsaved changes) it asks first, unless `force` (e.g. right after saving).
 */
export function navigate(href: string, { replace = false, force = false }: { replace?: boolean; force?: boolean } = {}): void {
  if (blocker && !force) {
    blocker(() => navigate(href, { replace, force: true }))
    return
  }
  if (replace) {
    window.history.replaceState({ idx: currentIndex }, '', href)
  } else {
    currentIndex += 1
    window.history.pushState({ idx: currentIndex }, '', href)
  }
  currentUrl = currentLocation()
  window.dispatchEvent(new Event(NAVIGATE_EVENT))
  window.scrollTo(0, 0)
}

/** The current path and query, e.g. to come back after signing in. */
export function currentLocation(): string {
  return `${window.location.pathname}${window.location.search}`
}

/** `/sign-in?next=…` that returns to `next` (default: here) after signing in. */
export function signInHref(next: string = currentLocation(), page: '/sign-in' | '/sign-up' = '/sign-in'): string {
  return next && next !== '/' ? `${page}?next=${encodeURIComponent(next)}` : page
}

/**
 * The `?next=` target, only if it is a path on this site (never `//host` or `https://…`, which
 * would make the sign-in page an open redirect) and not another sign-in page.
 */
export function safeNext(): string {
  const next = new URLSearchParams(window.location.search).get('next') ?? ''
  if (!next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return '/'
  if (isAuthRoute(matchRoute(next.split('?')[0]))) return '/'
  return next
}

/** Merges `updates` into the current query string without adding a history entry. `null` removes a key. */
export function updateQuery(updates: Record<string, string | null>): void {
  const params = new URLSearchParams(window.location.search)
  for (const [key, value] of Object.entries(updates)) {
    if (value === null || value === '') params.delete(key)
    else params.set(key, value)
  }
  const query = params.toString()
  const url = `${window.location.pathname}${query ? `?${query}` : ''}`
  if (url !== `${window.location.pathname}${window.location.search}`) {
    window.history.replaceState(window.history.state, '', url)
    currentUrl = url
  }
}
