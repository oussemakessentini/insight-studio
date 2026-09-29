import { useSyncExternalStore } from 'react'

// A minimal History API router: three routes don't justify a routing dependency.

export type Route =
  | { name: 'dashboard' }
  | { name: 'products' }
  | { name: 'product'; productId: number }
  | { name: 'notFound' }

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

// Counts back/forward navigations. Registered at import time so it runs before React's own
// popstate subscribers read the snapshot.
let historyVersion = 0
window.addEventListener('popstate', () => {
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
  return { name: 'notFound' }
}

export function navigate(href: string): void {
  window.history.pushState(null, '', href)
  window.dispatchEvent(new Event(NAVIGATE_EVENT))
  window.scrollTo(0, 0)
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
    window.history.replaceState(null, '', url)
  }
}
