import { useState } from 'react'
import { ApiError } from '../api/client'
import { dashboardsApi, type Dashboard, type DashboardRevisionSummary } from '../api/dashboards'
import { DashboardGrid } from '../components/dashboards/DashboardGrid'
import { WidgetCard } from '../components/dashboards/WidgetCard'
import { RefreshIcon } from '../components/Icons'
import { Link } from '../components/Link'
import { PageHeader } from '../components/PageHeader'
import { AsyncContent, EmptyState, ErrorState, Panel, Skeleton, SkeletonRows } from '../components/Panel'
import { useApi } from '../hooks/useApi'
import { useMediaQuery } from '../hooks/useMediaQuery'
import { DESKTOP_MIN_WIDTH, GRID_LABELS } from '../lib/dashboardLayout'
import { dashboardPermissions, revisionParam, widgetCountLabel } from '../lib/dashboards'
import { formatDateTimeLong } from '../lib/format'
import { useSearch } from '../lib/router'
import '../styles/accounts.css'
import '../styles/reports.css'
import '../styles/charts.css'
import '../styles/dashboards.css'
import { VerifyToManageDashboards } from './DashboardsPage'
import type { PageProps } from './types'

/** A dashboard: its charts on the desktop or mobile grid, each run with today's data. */
export function DashboardViewPage({ dashboardId, context, href }: PageProps & { dashboardId: number }) {
  const { business } = context
  const can = dashboardPermissions(context.access)
  const requested = revisionParam(useSearch())
  const desktop = useMediaQuery(`(min-width: ${DESKTOP_MIN_WIDTH}px)`)
  // Refresh reloads the dashboard (live chart titles) and reruns every widget.
  const [refreshKey, setRefreshKey] = useState(0)
  const dashboard = useApi(`dashboard|${dashboardId}|${requested ?? 'current'}|${refreshKey}`, (signal) =>
    dashboardsApi.get(dashboardId, requested, signal),
  )
  const revisions = useApi(`dashboard-revisions|${dashboardId}|${refreshKey}`, (signal) => dashboardsApi.revisions(dashboardId, signal))
  const listHref = href('/dashboards')
  const viewHref = href(`/dashboards/${dashboardId}`)
  const editHref = href(`/dashboards/${dashboardId}/edit`)

  // The newest revision is the current one; until the list is in, a requested revision counts as older.
  const currentRevision = revisions.data?.[0]?.revision ?? null
  const older = requested !== null && requested !== currentRevision ? requested : null
  const shown = dashboard.data
  const olderSummary = older === null ? undefined : revisions.data?.find((r) => r.revision === older)

  if (dashboard.error instanceof ApiError && dashboard.error.status === 404) {
    return older !== null ? (
      <div className="panel page-error">
        <h1 className="page-title">Revision not found</h1>
        <ErrorState message={`This dashboard has no revision ${older}.`} />
        <p className="page-error-action">
          <Link className="button button-secondary" href={viewHref}>
            See the current version
          </Link>
        </p>
      </div>
    ) : (
      <DashboardNotFound listHref={listHref} />
    )
  }
  if (dashboard.error && !shown) {
    return (
      <div className="panel page-error">
        <h1 className="page-title">This dashboard couldn’t be loaded</h1>
        <ErrorState message={dashboard.error.message} onRetry={dashboard.retry} />
      </div>
    )
  }

  const missing = shown?.widgets.filter((w) => w.missing || w.chart === null).length ?? 0
  const grid = shown ? (desktop ? shown.layout.desktop : shown.layout.mobile) : null

  return (
    <>
      <PageHeader
        eyebrow={
          <nav aria-label="Breadcrumb" className="breadcrumb">
            <Link href={listHref}>Dashboards</Link>
          </nav>
        }
        title={shown ? <span className="break-anywhere">{shown.name}</span> : <Skeleton height={30} width={240} />}
        subtitle={shown ? <DashboardDetails dashboard={shown} timeZone={business.timeZone} layoutLabel={GRID_LABELS[desktop ? 'desktop' : 'mobile']} /> : undefined}
      >
        {shown && (
          <div className="saved-actions">
            <button
              type="button"
              className="button button-secondary dashboard-refresh"
              onClick={() => setRefreshKey((k) => k + 1)}
              disabled={dashboard.loading}
            >
              <RefreshIcon width={16} height={16} />
              Refresh
            </button>
            {can.canManage && older === null && (
              <Link className="button button-primary" href={editHref}>
                Edit
              </Link>
            )}
          </div>
        )}
      </PageHeader>

      {can.needsVerification && <VerifyToManageDashboards />}
      {dashboard.error && shown && <ErrorState message={dashboard.error.message} onRetry={dashboard.retry} />}

      {older !== null && (
        <div className="form-alert chart-revision-note" role="note">
          <div>
            You’re looking at <strong>revision {older}</strong>
            {olderSummary
              ? ` saved ${formatDateTimeLong(olderSummary.createdAt, business.timeZone)}${olderSummary.createdBy ? ` by ${olderSummary.createdBy}` : ''}`
              : ''}
            . It is
            read-only, and its charts show their current settings and today’s data
            {currentRevision !== null ? `; the dashboard now uses revision ${currentRevision}` : ''}.{' '}
            <Link className="form-link" href={viewHref}>
              See the current version
            </Link>
          </div>
        </div>
      )}

      {missing > 0 && older === null && (
        <div className="form-alert dashboard-missing-note" role="note">
          <div>
            {missing === 1 ? 'One chart on this dashboard was deleted.' : `${missing} charts on this dashboard were deleted.`}{' '}
            {can.canManage ? (
              <>
                Its place stays empty until it is removed.{' '}
                <Link className="form-link" href={editHref}>
                  Edit the dashboard
                </Link>
              </>
            ) : (
              'An owner or admin can remove the empty places.'
            )}
          </div>
        </div>
      )}

      {!shown || !grid ? (
        <div className="dashboard-grid-skeleton" aria-busy="true" aria-label="Loading">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="panel">
              <Skeleton height={300} />
            </div>
          ))}
        </div>
      ) : grid.items.length === 0 ? (
        <Panel title="No charts yet">
          <div className="empty-with-action">
            <EmptyState
              message={
                can.canManage && older === null
                  ? 'Add saved charts to this dashboard, then arrange them.'
                  : 'Owners and admins add charts to dashboards.'
              }
            />
            {can.canManage && older === null && (
              <Link className="button button-primary" href={editHref}>
                Add charts
              </Link>
            )}
          </div>
        </Panel>
      ) : (
        <DashboardGrid grid={grid}>
          {(item) => {
            const widget = shown.widgets.find((w) => w.id === item.id)
            return widget ? (
              <WidgetCard
                widget={widget}
                refreshKey={refreshKey}
                canManage={can.canManage}
                href={href}
                editHref={older === null ? editHref : null}
              />
            ) : null
          }}
        </DashboardGrid>
      )}

      <Panel title="Revisions" subtitle="Every save keeps the previous version. Open one to see how the dashboard was arranged, read-only.">
        <AsyncContent {...revisions} skeleton={<SkeletonRows rows={3} />}>
          {(list) => (
            <DashboardRevisionList
              revisions={list}
              current={currentRevision}
              shown={older ?? currentRevision}
              viewHref={viewHref}
              timeZone={business.timeZone}
            />
          )}
        </AsyncContent>
      </Panel>
    </>
  )
}

/** "4 charts · Desktop layout · Revision 3 · Saved Oct 2, 2026, 8:00 AM by Ana" */
function DashboardDetails({ dashboard, timeZone, layoutLabel }: { dashboard: Dashboard; timeZone: string; layoutLabel: string }) {
  return (
    <span className="saved-details">
      <span>{widgetCountLabel(dashboard.layout.widgets.length)}</span>
      <span>{layoutLabel} layout</span>
      <span>Revision {dashboard.revision}</span>
      <span>
        Saved {formatDateTimeLong(dashboard.updatedAt, timeZone)}
        {dashboard.updatedBy && ` by ${dashboard.updatedBy}`}
      </span>
    </span>
  )
}

function DashboardRevisionList({
  revisions,
  current,
  shown,
  viewHref,
  timeZone,
}: {
  revisions: DashboardRevisionSummary[]
  current: number | null
  shown: number | null
  viewHref: string
  timeZone: string
}) {
  const withRevision = (n: number) => {
    const [path, query = ''] = viewHref.split('?')
    const params = new URLSearchParams(query)
    params.set('revision', String(n))
    return `${path}?${params}`
  }
  return (
    <ol className="chart-revisions dashboard-revisions">
      {revisions.map((r) => {
        const isCurrent = r.revision === current
        const isShown = r.revision === shown
        return (
          <li key={r.revision} className={isShown ? 'is-shown' : undefined}>
            <span className="chart-revision-main">
              {isShown ? (
                <strong aria-current="page">Revision {r.revision}</strong>
              ) : (
                <Link className="cell-link" href={isCurrent ? viewHref : withRevision(r.revision)}>
                  Revision {r.revision}
                </Link>
              )}
              {isCurrent && <span className="chip">Current</span>}
            </span>
            <span className="cell-secondary break-anywhere">
              {r.name} · {widgetCountLabel(r.widgetCount)}
            </span>
            <span className="cell-secondary">
              {formatDateTimeLong(r.createdAt, timeZone)}
              {r.createdBy && ` · ${r.createdBy}`}
            </span>
          </li>
        )
      })}
    </ol>
  )
}

function DashboardNotFound({ listHref }: { listHref: string }) {
  return (
    <div className="panel page-error">
      <h1 className="page-title">Dashboard not found</h1>
      <ErrorState message="It may have been deleted, or it belongs to another business." />
      <p className="page-error-action">
        <Link className="button button-secondary" href={listHref}>
          See all dashboards
        </Link>
      </p>
    </div>
  )
}
