import { useCallback, useEffect, useEffectEvent, useState, type ReactNode } from 'react'
import { dashboardApi } from './api/client'
import type { DashboardContext } from './api/types'
import { LogoMark, MenuIcon } from './components/Icons'
import { Link } from './components/Link'
import { ErrorState, Skeleton } from './components/Panel'
import { Sidebar, type Section } from './components/Sidebar'
import { useApi } from './hooks/useApi'
import { filterQuery, filtersFromUrl, withFilters, type Filters } from './lib/filters'
import { useStateResetOn } from './hooks/useStateResetOn'
import { matchRoute, updateQuery, useHistoryVersion, usePathname, type Route } from './lib/router'
import { DashboardPage } from './pages/DashboardPage'
import { ProductDetailPage } from './pages/ProductDetailPage'
import { ProductsPage } from './pages/ProductsPage'
import { SaleDetailPage } from './pages/SaleDetailPage'
import { SalesPage } from './pages/SalesPage'
import type { PageProps } from './pages/types'

function App() {
  const context = useApi('context', (signal) => dashboardApi.context(signal))
  const route = matchRoute(usePathname())

  if (context.data) {
    return <Workspace context={context.data} route={route} />
  }
  return (
    <Shell active={sectionOf(route)} hrefs={{ dashboard: '/', products: '/products', sales: '/sales' }}>
      {context.error ? (
        <div className="panel page-error">
          <h1 className="page-title">Insight Studio is unavailable</h1>
          <ErrorState message={context.error.message} onRetry={context.retry} />
        </div>
      ) : (
        <PageSkeleton />
      )}
    </Shell>
  )
}

/**
 * Owns the store/date filters shared by every page. They live in the URL so views can be
 * bookmarked, and survive navigation because this component stays mounted across routes.
 */
function Workspace({ context, route }: { context: DashboardContext; route: Route }) {
  // Browser back/forward re-reads the filters recorded in the restored history entry. In-app
  // links carry the filters in their href, so the URL and this state never disagree.
  const historyVersion = useHistoryVersion()
  const [filters, setFilterState] = useStateResetOn<Filters>(String(historyVersion), fromUrl(context))

  const setFilters = useCallback(
    (next: Filters) => {
      setFilterState(next)
      updateQuery(filterQuery(next))
    },
    [setFilterState],
  )

  // Once on load: drop invalid or default-valued filter parameters from the address bar.
  const normalizeUrl = useEffectEvent(() => updateQuery(filterQuery(filters)))
  useEffect(() => normalizeUrl(), [])

  const href = useCallback((path: string) => withFilters(path, filters), [filters])
  const pageProps: PageProps = { context, filters, onFiltersChange: setFilters, href }

  return (
    <Shell
      active={sectionOf(route)}
      hrefs={{ dashboard: href('/'), products: href('/products'), sales: href('/sales') }}
      businessName={context.business.name}
    >
      {route.name === 'dashboard' && <DashboardPage {...pageProps} />}
      {route.name === 'products' && <ProductsPage {...pageProps} />}
      {/* Keyed so switching products starts from a clean state. */}
      {route.name === 'product' && <ProductDetailPage key={route.productId} productId={route.productId} {...pageProps} />}
      {route.name === 'sales' && <SalesPage {...pageProps} />}
      {route.name === 'sale' && <SaleDetailPage key={route.saleId} saleId={route.saleId} {...pageProps} />}
      {route.name === 'notFound' && <NotFound homeHref={href('/')} />}
    </Shell>
  )
}

function fromUrl(context: DashboardContext): Filters {
  return filtersFromUrl(
    context.dataRange,
    context.stores.map((s) => s.id),
  )
}

function sectionOf(route: Route): Section | null {
  if (route.name === 'dashboard') return 'dashboard'
  if (route.name === 'products' || route.name === 'product') return 'products'
  if (route.name === 'sales' || route.name === 'sale') return 'sales'
  return null
}

interface ShellProps {
  active: Section | null
  hrefs: Record<Section, string>
  businessName?: string
  children: ReactNode
}

function Shell({ active, hrefs, businessName, children }: ShellProps) {
  const [menuOpen, setMenuOpen] = useState(false)
  return (
    <div className="app-shell">
      <Sidebar
        businessName={businessName}
        active={active}
        hrefs={hrefs}
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
      />
      <div className="app-main">
        <div className="topbar">
          <button
            type="button"
            className="icon-button"
            onClick={() => setMenuOpen(true)}
            aria-label="Open menu"
            aria-expanded={menuOpen}
            aria-controls="sidebar"
          >
            <MenuIcon />
          </button>
          <LogoMark width={24} height={24} />
          <span className="topbar-title">Insight Studio</span>
        </div>
        <main className="content">{children}</main>
      </div>
    </div>
  )
}

function NotFound({ homeHref }: { homeHref: string }) {
  return (
    <div className="panel page-error">
      <h1 className="page-title">Page not found</h1>
      <ErrorState message="There is nothing at this address." />
      <p className="page-error-action">
        <Link className="button button-secondary" href={homeHref}>
          Go to the dashboard
        </Link>
      </p>
    </div>
  )
}

function PageSkeleton() {
  return (
    <div aria-busy="true" aria-label="Loading">
      <div className="page-header">
        <div>
          <Skeleton height={14} width={160} />
          <Skeleton height={30} width={240} />
        </div>
      </div>
      <div className="metric-grid">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="metric-card">
            <Skeleton height={14} width="50%" />
            <Skeleton height={32} width="70%" />
          </div>
        ))}
      </div>
    </div>
  )
}

export default App
