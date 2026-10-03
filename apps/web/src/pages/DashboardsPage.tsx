import { useState } from 'react'
import { dashboardsApi, type DashboardSummary } from '../api/dashboards'
import { DashboardNameDialog } from '../components/dashboards/DashboardNameDialog'
import { FormError, FormSuccess } from '../components/Form'
import { Link } from '../components/Link'
import { PageHeader } from '../components/PageHeader'
import { AsyncContent, ErrorState, Panel, SkeletonRows } from '../components/Panel'
import { ResendVerification } from '../components/VerifyEmailBanner'
import { useApi } from '../hooks/useApi'
import { dashboardPermissions, MAX_DASHBOARDS, widgetCountLabel } from '../lib/dashboards'
import { withoutMissingWidgets } from '../lib/dashboardLayout'
import { formatDateTime } from '../lib/format'
import { navigate, signInHref } from '../lib/router'
import { errorMessage } from '../lib/validation'
import '../styles/accounts.css'
import '../styles/reports.css'
import '../styles/dashboards.css'
import type { PageProps } from './types'

type Notice = { kind: 'success' | 'error'; message: string; href?: string } | null

/** Every custom dashboard of the business; owners and admins also create and manage them here. */
export function DashboardsPage({ context, href }: PageProps) {
  const { business } = context
  const can = dashboardPermissions(context.access)
  const [version, setVersion] = useState(0)
  const [notice, setNotice] = useState<Notice>(null)
  const [creating, setCreating] = useState(false)
  const [renaming, setRenaming] = useState<DashboardSummary | null>(null)
  const list = useApi(`dashboards|${version}`, (signal) => dashboardsApi.list(signal))

  return (
    <>
      <PageHeader
        eyebrow={business.name}
        title="Dashboards"
        subtitle={
          <>
            Saved charts arranged on one page
            <span className="page-subtitle-muted"> · Dates in {business.timeZone}</span>
          </>
        }
      >
        {can.canManage && (
          <button type="button" className="button button-primary" onClick={() => setCreating(true)}>
            New dashboard
          </button>
        )}
      </PageHeader>

      {can.needsVerification && <VerifyToManageDashboards />}

      <Panel
        title="All dashboards"
        subtitle={
          can.canManage
            ? 'Open a dashboard to see its charts with today’s data, or edit how they are arranged.'
            : 'Open a dashboard to see its charts with today’s data. Owners and admins create and arrange dashboards.'
        }
      >
        <div className="form-stack">
          <div aria-live="polite">
            {notice?.kind === 'success' && (
              <FormSuccess>
                {notice.message}{' '}
                {notice.href && (
                  <Link className="form-link" href={notice.href}>
                    Open it
                  </Link>
                )}
              </FormSuccess>
            )}
            {notice?.kind === 'error' && <FormError>{notice.message}</FormError>}
          </div>
          <AsyncContent {...list} skeleton={<SkeletonRows rows={4} />}>
            {(dashboards) =>
              dashboards.length === 0 ? (
                <EmptyDashboards canManage={can.canManage} onCreate={() => setCreating(true)} />
              ) : (
                <DashboardTable
                  dashboards={dashboards}
                  canManage={can.canManage}
                  timeZone={business.timeZone}
                  href={href}
                  onRename={setRenaming}
                  onDuplicated={(copy) => {
                    setNotice({ kind: 'success', message: `Duplicated as “${copy.name}”.`, href: href(`/dashboards/${copy.id}`) })
                    setVersion((v) => v + 1)
                  }}
                  onDeleted={(dashboard) => {
                    setNotice({ kind: 'success', message: `Deleted “${dashboard.name}”.` })
                    setVersion((v) => v + 1)
                  }}
                  onError={(message) => setNotice({ kind: 'error', message })}
                />
              )
            }
          </AsyncContent>
        </div>
      </Panel>

      {can.canManage && (
        <>
          <DashboardNameDialog
            open={creating}
            title="New dashboard"
            initialName=""
            submitLabel="Create and add charts"
            busyLabel="Creating…"
            note={`Name it now; you’ll add and arrange charts next. A business can have up to ${MAX_DASHBOARDS} dashboards.`}
            onSubmit={async (name) => {
              const created = await dashboardsApi.create(name)
              setCreating(false)
              navigate(href(`/dashboards/${created.id}/edit`))
            }}
            onClose={() => setCreating(false)}
          />
          <DashboardNameDialog
            // Keyed so each dashboard's dialog starts from its own name.
            key={renaming?.id ?? 'none'}
            open={renaming !== null}
            title="Rename dashboard"
            initialName={renaming?.name ?? ''}
            submitLabel="Rename"
            busyLabel="Renaming…"
            note={
              renaming && renaming.missingCount > 0
                ? `Renaming saves a new revision. It also removes the ${renaming.missingCount === 1 ? 'placeholder of a deleted chart' : `placeholders of ${renaming.missingCount} deleted charts`}: a dashboard can only be saved without them.`
                : 'Renaming saves a new revision; the charts stay where they are.'
            }
            onSubmit={async (name) => {
              if (!renaming) return
              // A rename is a save of the whole dashboard: read the current layout and revision first.
              const current = await dashboardsApi.get(renaming.id)
              await dashboardsApi.update(current.id, name, withoutMissingWidgets(current.layout, current.widgets), current.revision)
              setRenaming(null)
              setNotice({ kind: 'success', message: `Renamed to “${name}”.` })
              setVersion((v) => v + 1)
            }}
            onClose={() => setRenaming(null)}
          />
        </>
      )}
    </>
  )
}

function EmptyDashboards({ canManage, onCreate }: { canManage: boolean; onCreate: () => void }) {
  return (
    <div className="state state-empty saved-empty">
      <p className="saved-empty-title">No dashboards yet</p>
      <p>
        {canManage
          ? 'Put saved charts side by side on one page: create a dashboard, add charts, then drag them into place (or use the move and resize buttons).'
          : 'Owners and admins arrange saved charts into dashboards. Once they do, you can open them here.'}
      </p>
      {canManage && (
        <button type="button" className="button button-primary" onClick={onCreate}>
          New dashboard
        </button>
      )}
    </div>
  )
}

/** For an OWNER or ADMIN who hasn't verified their email address yet. */
export function VerifyToManageDashboards() {
  return (
    <div className="form-alert saved-verify" role="note">
      <div>
        Verify your email address to create, arrange, rename, duplicate or delete dashboards. Until then you can open them.{' '}
        <ResendVerification />
      </div>
    </div>
  )
}

interface DashboardTableProps {
  dashboards: DashboardSummary[]
  canManage: boolean
  timeZone: string
  href: (path: string) => string
  onRename: (dashboard: DashboardSummary) => void
  onDuplicated: (copy: { id: number; name: string }) => void
  onDeleted: (dashboard: DashboardSummary) => void
  onError: (message: string) => void
}

function DashboardTable({ dashboards, canManage, timeZone, href, onRename, onDuplicated, onDeleted, onError }: DashboardTableProps) {
  const [busy, setBusy] = useState<number | null>(null)
  const [confirming, setConfirming] = useState<number | null>(null)

  const run = async (dashboard: DashboardSummary, action: () => Promise<void>) => {
    setBusy(dashboard.id)
    try {
      await action()
    } catch (err) {
      onError(errorMessage(err))
    } finally {
      setBusy(null)
    }
  }

  const duplicate = (dashboard: DashboardSummary) =>
    run(dashboard, async () => {
      const copy = await dashboardsApi.duplicate(dashboard.id)
      onDuplicated(copy)
    })

  const remove = (dashboard: DashboardSummary) =>
    run(dashboard, async () => {
      await dashboardsApi.remove(dashboard.id)
      setConfirming(null)
      onDeleted(dashboard)
    })

  return (
    <div className="table-scroll">
      <table className="data-table saved-table">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col" className="num hide-sm">
              Charts
            </th>
            <th scope="col" className="hide-sm">
              Deleted charts
            </th>
            <th scope="col" className="hide-md">
              Updated
            </th>
            {canManage && (
              <th scope="col" className="num">
                <span className="visually-hidden">Actions</span>
              </th>
            )}
          </tr>
        </thead>
        <tbody>
          {dashboards.map((dashboard) => (
            <tr key={dashboard.id}>
              <td>
                <Link className="cell-link cell-primary break-anywhere" href={href(`/dashboards/${dashboard.id}`)}>
                  {dashboard.name}
                </Link>
                {/* On small screens the hidden columns' facts move under the name. */}
                <span className="cell-secondary show-sm">
                  {widgetCountLabel(dashboard.widgetCount)}
                  {dashboard.missingCount > 0 && ` · ${dashboard.missingCount} deleted`}
                </span>
                <span className="cell-secondary show-sm">Updated {formatDateTime(dashboard.updatedAt, timeZone)}</span>
              </td>
              <td className="num hide-sm">{dashboard.widgetCount}</td>
              <td className="hide-sm nowrap">
                {dashboard.missingCount > 0 ? (
                  <span className="chip dashboard-missing-chip">{dashboard.missingCount} to remove</span>
                ) : (
                  <span className="text-muted">None</span>
                )}
              </td>
              <td className="hide-md nowrap">
                <span className="cell-primary">{formatDateTime(dashboard.updatedAt, timeZone)}</span>
                <span className="cell-secondary">
                  {dashboard.updatedBy ? `by ${dashboard.updatedBy} · ` : ''}revision {dashboard.revision}
                </span>
              </td>
              {canManage && (
                <td className="num">
                  {confirming === dashboard.id ? (
                    <span className="confirm-inline saved-row-actions" role="group" aria-label={`Delete ${dashboard.name}?`}>
                      <span className="confirm-text">Delete it? Charts stay saved.</span>
                      <button
                        type="button"
                        className="button button-danger button-small"
                        disabled={busy !== null}
                        onClick={() => void remove(dashboard)}
                      >
                        {busy === dashboard.id ? 'Deleting…' : 'Delete'}
                      </button>
                      <button
                        type="button"
                        className="button button-secondary button-small"
                        disabled={busy !== null}
                        onClick={() => setConfirming(null)}
                      >
                        Cancel
                      </button>
                    </span>
                  ) : (
                    <span className="confirm-inline saved-row-actions">
                      <button
                        type="button"
                        className="button button-secondary button-small"
                        disabled={busy !== null}
                        onClick={() => onRename(dashboard)}
                        aria-label={`Rename ${dashboard.name}`}
                      >
                        Rename
                      </button>
                      <button
                        type="button"
                        className="button button-secondary button-small"
                        disabled={busy !== null}
                        onClick={() => void duplicate(dashboard)}
                        aria-label={`Duplicate ${dashboard.name}`}
                      >
                        {busy === dashboard.id ? 'Duplicating…' : 'Duplicate'}
                      </button>
                      <button
                        type="button"
                        className="button button-secondary button-small"
                        disabled={busy !== null}
                        onClick={() => setConfirming(dashboard.id)}
                        aria-label={`Delete ${dashboard.name}`}
                      >
                        Delete
                      </button>
                    </span>
                  )}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Dashboards in the anonymous public demo: they belong to a business, so sign in first. */
export function DashboardsSignIn() {
  return (
    <div className="panel page-error">
      <h1 className="page-title">Dashboards need an account</h1>
      <ErrorState message="The demo shows the overview and reports; arranging saved charts into dashboards is for members of a business. Sign in or create an account to build your own." />
      <p className="page-error-action">
        <Link className="button button-primary" href={signInHref()}>
          Sign in
        </Link>
      </p>
    </div>
  )
}
