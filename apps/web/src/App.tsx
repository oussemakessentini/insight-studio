import { useCallback, useEffect, useEffectEvent, useLayoutEffect, useState, type ReactNode } from 'react'
import { ApiError, dashboardApi, setBusinessScope, UNAUTHORIZED_EVENT } from './api/client'
import type { BusinessAccess, DashboardContext } from './api/types'
import { DemoBanner } from './components/DemoBanner'
import { ResendVerification, VerifyEmailBanner } from './components/VerifyEmailBanner'
import { LogoMark, MenuIcon } from './components/Icons'
import { Link } from './components/Link'
import { ErrorState, Skeleton } from './components/Panel'
import { Redirect } from './components/Redirect'
import { Sidebar, type Section } from './components/Sidebar'
import { SignOutButton } from './components/SignOutButton'
import { useApi } from './hooks/useApi'
import { useSessionLoader } from './hooks/useSessionLoader'
import { useStateResetOn } from './hooks/useStateResetOn'
import { filterQuery, filtersFromUrl, withFilters, type Filters } from './lib/filters'
import { chartPermissions } from './lib/charts'
import { dashboardPermissions } from './lib/dashboards'
import { savedReportPermissions } from './lib/savedReports'
import { isAdminOrOwner } from './lib/settingsAccess'
import {
  currentLocation,
  isAuthRoute,
  matchRoute,
  navigate,
  requiresAccount,
  safeNext,
  signInHref,
  updateQuery,
  useHistoryVersion,
  usePathname,
  type Route,
} from './lib/router'
import {
  readStoredBusinessId,
  resolveBusinessId,
  SessionContext,
  storeBusinessId,
  useLoadedSession,
  type SessionContextValue,
} from './lib/session'
import { AccountPage } from './pages/AccountPage'
import { ActivityPage } from './pages/ActivityPage'
import { BusinessSettingsPage } from './pages/BusinessSettingsPage'
import { ForgotPasswordPage } from './pages/auth/ForgotPasswordPage'
import { InvitePage } from './pages/auth/InvitePage'
import { VerifyEmailPage } from './pages/auth/VerifyEmailPage'
import { ResetPasswordPage } from './pages/auth/ResetPasswordPage'
import { SignInPage } from './pages/auth/SignInPage'
import { SignUpPage } from './pages/auth/SignUpPage'
import { CatalogPage } from './pages/CatalogPage'
import { ChartBuilderPage } from './pages/ChartBuilderPage'
import { ChartPage } from './pages/ChartPage'
import { ChartsPage, ChartsSignIn } from './pages/ChartsPage'
import { DashboardPage } from './pages/DashboardPage'
import { DashboardsPage, DashboardsSignIn } from './pages/DashboardsPage'
import { DashboardEditPage } from './pages/DashboardEditPage'
import { DashboardViewPage } from './pages/DashboardViewPage'
import { ImportDetailPage } from './pages/ImportDetailPage'
import { ImportsPage } from './pages/ImportsPage'
import { MembersPage } from './pages/MembersPage'
import { NewBusinessPage } from './pages/NewBusinessPage'
import { ProductDetailPage } from './pages/ProductDetailPage'
import { ProductsPage } from './pages/ProductsPage'
import { ReportsPage } from './pages/ReportsPage'
import { SavedReportPage } from './pages/SavedReportPage'
import { SavedReportsPage, SavedReportsSignIn } from './pages/SavedReportsPage'
import { SaleDetailPage } from './pages/SaleDetailPage'
import { SalesPage } from './pages/SalesPage'
import { StoreDetailPage } from './pages/StoreDetailPage'
import { StoresPage } from './pages/StoresPage'
import type { PageProps } from './pages/types'
import './styles/accounts.css'

/** Loads the session, then shows the page for who is (or isn't) signed in. */
function App() {
  const route = matchRoute(usePathname())
  const { session, error, reload } = useSessionLoader()
  const [storedBusinessId, setStoredBusinessId] = useState(readStoredBusinessId)

  // A business call answered 401: the session ended (expired, signed out elsewhere, password
  // reset). Re-read the session and ask to sign in again, coming back here afterwards.
  const onUnauthorized = useEffectEvent(() => {
    if (isAuthRoute(matchRoute(window.location.pathname))) return
    const target = signInHref(currentLocation())
    reload(() => target).catch(() => navigate(target, { replace: true }))
  })
  useEffect(() => {
    const handler = () => onUnauthorized()
    window.addEventListener(UNAUTHORIZED_EVENT, handler)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, handler)
  }, [])

  const selectBusiness = useCallback((businessId: number, to: string | null = '/') => {
    storeBusinessId(businessId)
    setStoredBusinessId(businessId)
    // Opening the dashboard without query parameters also resets the shared filters: store ids
    // belong to one business.
    if (to !== null) navigate(to)
  }, [])

  if (!session) {
    return (
      <Shell active={sectionOf(route)} hrefs={sectionHrefs((path) => path)} access={null}>
        {error ? <Unavailable message={error.message} onRetry={() => void reload().catch(() => undefined)} /> : <PageSkeleton />}
      </Shell>
    )
  }

  const value: SessionContextValue = {
    session,
    reload,
    businessId: resolveBusinessId(session, storedBusinessId),
    selectBusiness,
  }
  return (
    <SessionContext value={value}>
      <Screen route={route} />
    </SessionContext>
  )
}

function Screen({ route }: { route: Route }) {
  const { session, businessId } = useLoadedSession()

  // Invitation links work signed in or out, with or without a business.
  if (route.name === 'invite') return <InvitePage />
  // Verification links work in any browser, signed in or out.
  if (route.name === 'verifyEmail') return <VerifyEmailPage />

  if (isAuthRoute(route)) {
    // Signed-in users skip sign-in and sign-up; password recovery works either way.
    if (session.authenticated && (route.name === 'signIn' || route.name === 'signUp')) return <Redirect to={safeNext()} />
    if (route.name === 'signIn') return <SignInPage />
    if (route.name === 'signUp') return <SignUpPage />
    if (route.name === 'forgotPassword') return <ForgotPasswordPage />
    return <ResetPasswordPage />
  }

  if (!session.authenticated) {
    // Anonymous visitors see the read-only demo if the API offers one; otherwise they sign in.
    if (requiresAccount(route) || !session.demo?.enabled) return <Redirect to={signInHref()} />
    return <BusinessWorkspace key="demo" businessId={null} route={route} />
  }

  if (businessId === null) {
    // Signed in without a business: onboarding (or the account page, e.g. to sign out).
    if (route.name === 'account') {
      return (
        <OnboardingFrame>
          <AccountPage />
        </OnboardingFrame>
      )
    }
    if (route.name !== 'newBusiness') return <Redirect to="/businesses/new" />
    return (
      <OnboardingFrame>
        <NewBusinessPage onboarding />
      </OnboardingFrame>
    )
  }

  // Keyed by business: switching remounts everything, so no request or state of one business
  // leaks into another.
  return <BusinessWorkspace key={businessId} businessId={businessId} route={route} />
}

/** Loads the context of one business (null: the public demo) and shows the app for it. */
function BusinessWorkspace({ businessId, route }: { businessId: number | null; route: Route }) {
  const { reload } = useLoadedSession()
  // Before any request of this subtree (layout effects run before the pages' data effects).
  useLayoutEffect(() => setBusinessScope(businessId), [businessId])
  const context = useApi(`context|${businessId ?? 'demo'}`, (signal) => dashboardApi.context(signal))

  // 404 for a business we thought we belonged to: the membership was removed meanwhile. Re-read
  // the session so the app moves on to a business that is still ours.
  const membershipGone = businessId !== null && context.error instanceof ApiError && context.error.status === 404
  useEffect(() => {
    if (membershipGone) reload().catch(() => undefined)
  }, [membershipGone, reload])

  if (context.data) {
    // While a refresh is in flight the previous context stays on screen (useApi keeps the last data).
    return <Workspace context={context.data} route={route} refreshContext={context.retry} />
  }
  return (
    <Shell active={sectionOf(route)} hrefs={sectionHrefs((path) => path)} access={null} demo={businessId === null}>
      {context.error ? <Unavailable message={context.error.message} onRetry={context.retry} /> : <PageSkeleton />}
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
  const { session } = useLoadedSession()
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
  const { access } = context
  const noAccess = <NoAccess access={access} businessName={context.business.name} homeHref={href('/')} />
  // Saved reports belong to members; the public demo gets a sign-in prompt instead.
  const savedReportsAvailable = savedReportPermissions(access).available
  // Charts too: members only; building them is for verified owners and admins.
  const charts = chartPermissions(access)
  // Dashboards follow the same rules: members only; arranging them is for verified owners and admins.
  const dashboards = dashboardPermissions(access)

  return (
    <Shell
      active={sectionOf(route)}
      hrefs={sectionHrefs(href)}
      access={access}
      businessName={context.business.name}
      demo={!session.authenticated}
    >
      {route.name === 'dashboard' && <DashboardPage {...pageProps} />}
      {route.name === 'dashboards' && (dashboards.available ? <DashboardsPage {...pageProps} /> : <DashboardsSignIn />)}
      {route.name === 'dashboardView' &&
        (dashboards.available ? (
          <DashboardViewPage key={route.dashboardId} dashboardId={route.dashboardId} {...pageProps} />
        ) : (
          <DashboardsSignIn />
        ))}
      {route.name === 'dashboardEdit' &&
        (!dashboards.available ? (
          <DashboardsSignIn />
        ) : dashboards.canManage ? (
          <DashboardEditPage key={route.dashboardId} dashboardId={route.dashboardId} {...pageProps} />
        ) : (
          noAccess
        ))}
      {route.name === 'products' && <ProductsPage {...pageProps} />}
      {/* Keyed so switching products starts from a clean state. */}
      {route.name === 'product' && <ProductDetailPage key={route.productId} productId={route.productId} {...pageProps} />}
      {route.name === 'sales' && <SalesPage {...pageProps} />}
      {route.name === 'sale' && <SaleDetailPage key={route.saleId} saleId={route.saleId} {...pageProps} />}
      {route.name === 'stores' && <StoresPage {...pageProps} />}
      {route.name === 'store' && <StoreDetailPage key={route.storeId} storeId={route.storeId} {...pageProps} />}
      {route.name === 'reports' && <ReportsPage {...pageProps} />}
      {route.name === 'savedReports' && (savedReportsAvailable ? <SavedReportsPage {...pageProps} /> : <SavedReportsSignIn />)}
      {route.name === 'savedReport' &&
        (savedReportsAvailable ? (
          <SavedReportPage key={route.savedReportId} savedReportId={route.savedReportId} {...pageProps} />
        ) : (
          <SavedReportsSignIn />
        ))}
      {route.name === 'charts' && (charts.available ? <ChartsPage {...pageProps} /> : <ChartsSignIn />)}
      {route.name === 'chart' &&
        (charts.available ? <ChartPage key={route.chartId} chartId={route.chartId} {...pageProps} /> : <ChartsSignIn />)}
      {/* Role-gated pages: hidden as a convenience; the API refuses these actions regardless. */}
      {(route.name === 'chartNew' || route.name === 'chartEdit') &&
        (!charts.available ? (
          <ChartsSignIn />
        ) : charts.canManage ? (
          <ChartBuilderPage
            key={route.name === 'chartEdit' ? route.chartId : 'new'}
            chartId={route.name === 'chartEdit' ? route.chartId : null}
            {...pageProps}
          />
        ) : (
          noAccess
        ))}
      {route.name === 'imports' && (access.canImport ? <ImportsPage {...pageProps} /> : noAccess)}
      {route.name === 'import' &&
        (access.canImport ? <ImportDetailPage key={route.importId} importId={route.importId} {...pageProps} /> : noAccess)}
      {route.name === 'members' && (access.canManageMembers ? <MembersPage {...pageProps} /> : noAccess)}
      {route.name === 'catalog' && (access.canManageCatalog ? <CatalogPage {...pageProps} /> : noAccess)}
      {route.name === 'businessSettings' && (isAdminOrOwner(access) ? <BusinessSettingsPage {...pageProps} /> : noAccess)}
      {route.name === 'activity' && (isAdminOrOwner(access) ? <ActivityPage {...pageProps} /> : noAccess)}
      {route.name === 'account' && <AccountPage />}
      {route.name === 'newBusiness' && <NewBusinessPage />}
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
  switch (route.name) {
    case 'dashboard':
      return 'dashboard'
    case 'dashboards':
    case 'dashboardView':
    case 'dashboardEdit':
      return 'dashboards'
    case 'products':
    case 'product':
      return 'products'
    case 'sales':
    case 'sale':
      return 'sales'
    case 'stores':
    case 'store':
      return 'stores'
    case 'reports':
    case 'savedReports':
    case 'savedReport':
      return 'reports'
    case 'charts':
    case 'chartNew':
    case 'chart':
    case 'chartEdit':
      return 'charts'
    case 'imports':
    case 'import':
      return 'imports'
    case 'members':
      return 'members'
    case 'catalog':
      return 'catalog'
    case 'businessSettings':
      return 'business'
    case 'activity':
      return 'activity'
    case 'account':
      return 'account'
    case 'newBusiness':
      return 'newBusiness'
    default:
      return null
  }
}

function sectionHrefs(href: (path: string) => string): Record<Section, string> {
  return {
    dashboard: href('/'),
    dashboards: href('/dashboards'),
    products: href('/products'),
    sales: href('/sales'),
    stores: href('/stores'),
    reports: href('/reports'),
    charts: href('/charts'),
    imports: href('/imports'),
    members: href('/settings/members'),
    catalog: href('/settings/catalog'),
    business: href('/settings/business'),
    activity: href('/settings/activity'),
    account: href('/account'),
    newBusiness: href('/businesses/new'),
  }
}

interface ShellProps {
  active: Section | null
  hrefs: Record<Section, string>
  access: BusinessAccess | null
  businessName?: string
  /** The anonymous public demo: show the read-only banner. */
  demo?: boolean
  children: ReactNode
}

function Shell({ active, hrefs, access, businessName, demo = false, children }: ShellProps) {
  const [menuOpen, setMenuOpen] = useState(false)
  return (
    <div className="app-shell">
      <Sidebar
        businessName={businessName}
        access={access}
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
          {businessName && <span className="topbar-business">{businessName}</span>}
        </div>
        {demo && <DemoBanner />}
        {!demo && <VerifyEmailBanner />}
        <main className="content">{children}</main>
      </div>
    </div>
  )
}

/** Pages for a signed-in user without a business: no sidebar, just the account essentials. */
function OnboardingFrame({ children }: { children: ReactNode }) {
  return (
    <div className="auth-page onboarding-page">
      <header className="auth-header">
        <Link className="auth-brand" href="/">
          <LogoMark width={26} height={26} />
          <span>Insight Studio</span>
        </Link>
        <div className="auth-header-actions">
          <Link className="form-link" href="/account">
            Account
          </Link>
          <SignOutButton className="button button-secondary button-small" />
        </div>
      </header>
      <VerifyEmailBanner />
      <main className="onboarding-main">{children}</main>
    </div>
  )
}

function Unavailable({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="panel page-error">
      <h1 className="page-title">Insight Studio is unavailable</h1>
      <ErrorState message={message} onRetry={onRetry} />
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

/** A role-gated page the current user (or the demo) can't use. */
function NoAccess({ access, businessName, homeHref }: { access: BusinessAccess; businessName: string; homeHref: string }) {
  // readOnly is also true for signed-in VIEWERs; only the anonymous public demo has role DEMO.
  const demo = access.role === 'DEMO'
  if (!demo && !access.emailVerified) {
    return (
      <div className="panel page-error">
        <h1 className="page-title">Verify your email to continue</h1>
        <ErrorState message="Until your email address is verified you can look around, but not change anything. Open the link we emailed you." />
        <p className="page-error-action">
          <ResendVerification variant="button" />
        </p>
      </div>
    )
  }
  return (
    <div className="panel page-error">
      <h1 className="page-title">{demo ? 'The demo is read-only' : "You don't have access to this page"}</h1>
      <ErrorState
        message={
          demo
            ? 'Sign in or create an account to import sales and manage your own business.'
            : `Your role in ${businessName} doesn't include this. Ask an owner or admin if you need it.`
        }
      />
      <p className="page-error-action">
        {demo ? (
          <Link className="button button-primary" href={signInHref()}>
            Sign in
          </Link>
        ) : (
          <Link className="button button-secondary" href={homeHref}>
            Go to the dashboard
          </Link>
        )}
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
