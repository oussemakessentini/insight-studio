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
import { ImportDetailPage } from './pages/ImportDetailPage'
import { ImportsPage } from './pages/ImportsPage'
import { ProductDetailPage } from './pages/ProductDetailPage'
import { ProductsPage } from './pages/ProductsPage'
import { ReportsPage } from './pages/ReportsPage'
import { SaleDetailPage } from './pages/SaleDetailPage'
import { SalesPage } from './pages/SalesPage'
import { StoreDetailPage } from './pages/StoreDetailPage'
import { StoresPage } from './pages/StoresPage'
import type { PageProps } from './pages/types'

function App() {
  const context = useApi('context', (signal) => dashboardApi.context(signal))
  const route = matchRoute(usePathname())

  if (context.data) {
    // While a refresh is in flight the previous context stays on screen (useApi keeps the last data).
    return <Workspace context={context.data} route={route} refreshContext={context.retry} />
  }
  return (
    <Shell active={sectionOf(route)} hrefs={sectionHrefs((path) => path)} importsEnabled={false}>
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
function Workspace({
  context,
  route,
  refreshContext,
}: {
  context: DashboardContext
  route: Route
  refreshContext: () => void
}) {
  // Filters are re-read from the URL (which always mirrors them) when:
  // - the browser goes back/forward, restoring that history entry's filters;
  // - the data range changes (e.g. after an import), so relative presets such as "Last 30 days",
  //   which end on the last day with data, extend to the new dates. Custom ranges are kept.
  const historyVersion = useHistoryVersion()
  const resetKey = `${historyVersion}|${context.dataRange?.from ?? ''}|${context.dataRange?.to ?? ''}`
  const [filters, setFilterState] = useStateResetOn<Filters>(resetKey, fromUrl(context))

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
  const pageProps: PageProps = { context, filters, onFiltersChange: setFilters, href, refreshContext }

  return (
    <Shell
      active={sectionOf(route)}
      hrefs={sectionHrefs(href)}
      importsEnabled={context.features.importsEnabled}
      businessName={context.business.name}
    >
      {route.name === 'dashboard' && <DashboardPage {...pageProps} />}
      {route.name === 'products' && <ProductsPage {...pageProps} />}
      {/* Keyed so switching products starts from a clean state. */}
      {route.name === 'product' && <ProductDetailPage key={route.productId} productId={route.productId} {...pageProps} />}
      {route.name === 'sales' && <SalesPage {...pageProps} />}
      {route.name === 'sale' && <SaleDetailPage key={route.saleId} saleId={route.saleId} {...pageProps} />}
      {route.name === 'stores' && <StoresPage {...pageProps} />}
      {route.name === 'store' && <StoreDetailPage key={route.storeId} storeId={route.storeId} {...pageProps} />}
      {route.name === 'reports' && <ReportsPage {...pageProps} />}
      {/* Import pages exist only when the API has imports enabled (local development). */}
      {(route.name === 'imports' || route.name === 'import') && !context.features.importsEnabled && (
        <ImportsDisabled homeHref={href('/')} />
      )}
      {route.name === 'imports' && context.features.importsEnabled && <ImportsPage {...pageProps} />}
      {route.name === 'import' && context.features.importsEnabled && (
        <ImportDetailPage key={route.importId} importId={route.importId} {...pageProps} />
      )}
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
  if (route.name === 'stores' || route.name === 'store') return 'stores'
  if (route.name === 'reports') return 'reports'
  if (route.name === 'imports' || route.name === 'import') return 'imports'
  return null
}

function sectionHrefs(href: (path: string) => string): Record<Section, string> {
  return {
    dashboard: href('/'),
    products: href('/products'),
    sales: href('/sales'),
    stores: href('/stores'),
    reports: href('/reports'),
    imports: href('/imports'),
  }
}

interface ShellProps {
  active: Section | null
  hrefs: Record<Section, string>
  importsEnabled: boolean
  businessName?: string
  children: ReactNode
}

function Shell({ active, hrefs, importsEnabled, businessName, children }: ShellProps) {
  const [menuOpen, setMenuOpen] = useState(false)
  return (
    <div className="app-shell">
      <Sidebar
        businessName={businessName}
        active={active}
        hrefs={hrefs}
        importsEnabled={importsEnabled}
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

function ImportsDisabled({ homeHref }: { homeHref: string }) {
  return (
    <div className="panel page-error">
      <h1 className="page-title">Imports are disabled</h1>
      <ErrorState message="CSV import is only available in local development. Start the API with the 'local' profile to enable it." />
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
