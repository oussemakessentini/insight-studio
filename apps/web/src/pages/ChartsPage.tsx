import { useState } from 'react'
import { chartsApi, type ChartSummary } from '../api/charts'
import { ChartUsage } from '../components/charts/ChartUsage'
import { FormError, FormSuccess } from '../components/Form'
import { Link } from '../components/Link'
import { PageHeader } from '../components/PageHeader'
import { AsyncContent, ErrorState, Panel, SkeletonRows } from '../components/Panel'
import { ResendVerification } from '../components/VerifyEmailBanner'
import { useApi } from '../hooks/useApi'
import { chartPermissions, GROUP_BY_LABELS, METRIC_LABELS, VISUALIZATION_LABELS } from '../lib/charts'
import { formatDateTime } from '../lib/format'
import { signInHref } from '../lib/router'
import { errorMessage } from '../lib/validation'
import '../styles/accounts.css'
import '../styles/reports.css'
import '../styles/charts.css'
import type { PageProps } from './types'

type Notice = { kind: 'success' | 'error'; message: string; href?: string } | null

/** Every chart of the business; owners and admins also create, duplicate and delete them here. */
export function ChartsPage({ context, href }: PageProps) {
  const { business } = context
  const can = chartPermissions(context.access)
  const [version, setVersion] = useState(0)
  const [notice, setNotice] = useState<Notice>(null)
  const list = useApi(`charts|${version}`, (signal) => chartsApi.list(signal))

  return (
    <>
      <PageHeader
        eyebrow={business.name}
        title="Charts"
        subtitle={
          <>
            Saved charts of your sales
            <span className="page-subtitle-muted"> · Dates in {business.timeZone}</span>
          </>
        }
      >
        {can.canManage && (
          <Link className="button button-primary" href={href('/charts/new')}>
            New chart
          </Link>
        )}
      </PageHeader>

      {can.needsVerification && <VerifyToManageCharts />}

      <Panel
        title="All charts"
        subtitle={
          can.canManage
            ? 'Open a chart to see it with today’s data, edit it or look at earlier revisions.'
            : 'Open a chart to see it with today’s data. Owners and admins create and change charts.'
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
            {(charts) =>
              charts.length === 0 ? (
                <EmptyCharts canManage={can.canManage} newHref={href('/charts/new')} />
              ) : (
                <ChartTable
                  charts={charts}
                  canManage={can.canManage}
                  timeZone={business.timeZone}
                  href={href}
                  onDuplicated={(copy) => {
                    setNotice({ kind: 'success', message: `Duplicated as “${copy.title}”.`, href: href(`/charts/${copy.id}`) })
                    setVersion((v) => v + 1)
                  }}
                  onDeleted={(chart) => {
                    setNotice({ kind: 'success', message: `Deleted “${chart.title}”.` })
                    setVersion((v) => v + 1)
                  }}
                  onError={(message) => setNotice({ kind: 'error', message })}
                />
              )
            }
          </AsyncContent>
        </div>
      </Panel>
    </>
  )
}

function EmptyCharts({ canManage, newHref }: { canManage: boolean; newHref: string }) {
  return (
    <div className="state state-empty saved-empty">
      <p className="saved-empty-title">No charts yet</p>
      <p>
        {canManage
          ? 'Build a chart from your sales: pick what to show (revenue, orders, units), how to group it and which dates, preview it, then save it for everyone in the business.'
          : 'Owners and admins build charts. Once they do, you can open them here.'}
      </p>
      {canManage && (
        <Link className="button button-primary" href={newHref}>
          New chart
        </Link>
      )}
    </div>
  )
}

/** For an OWNER or ADMIN who hasn't verified their email address yet. */
export function VerifyToManageCharts() {
  return (
    <div className="form-alert saved-verify" role="note">
      <div>
        Verify your email address to build, change, duplicate or delete charts. Until then you can open them.{' '}
        <ResendVerification />
      </div>
    </div>
  )
}

interface ChartTableProps {
  charts: ChartSummary[]
  canManage: boolean
  timeZone: string
  href: (path: string) => string
  onDuplicated: (copy: { id: number; title: string }) => void
  onDeleted: (chart: ChartSummary) => void
  onError: (message: string) => void
}

function ChartTable({ charts, canManage, timeZone, href, onDuplicated, onDeleted, onError }: ChartTableProps) {
  const [busy, setBusy] = useState<number | null>(null)
  const [confirming, setConfirming] = useState<number | null>(null)

  const run = async (chart: ChartSummary, action: () => Promise<void>) => {
    setBusy(chart.id)
    try {
      await action()
    } catch (err) {
      onError(errorMessage(err))
    } finally {
      setBusy(null)
    }
  }

  const duplicate = (chart: ChartSummary) =>
    run(chart, async () => {
      const copy = await chartsApi.duplicate(chart.id)
      onDuplicated(copy)
    })

  const remove = (chart: ChartSummary) =>
    run(chart, async () => {
      await chartsApi.remove(chart.id)
      setConfirming(null)
      onDeleted(chart)
    })

  return (
    <div className="table-scroll">
      <table className="data-table saved-table">
        <thead>
          <tr>
            <th scope="col">Title</th>
            <th scope="col" className="hide-sm">
              Chart
            </th>
            <th scope="col" className="hide-sm">
              Metrics
            </th>
            <th scope="col" className="hide-sm">
              Grouped by
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
          {charts.map((chart) => {
            const metrics = chart.metrics.map((m) => METRIC_LABELS[m] ?? m).join(', ')
            const grouping = GROUP_BY_LABELS[chart.groupBy] ?? chart.groupBy
            return (
              <tr key={chart.id}>
                <td>
                  <Link className="cell-link cell-primary break-anywhere" href={href(`/charts/${chart.id}`)}>
                    {chart.title}
                  </Link>
                  {/* On small screens the hidden columns' facts move under the title. */}
                  <span className="cell-secondary show-sm">
                    {VISUALIZATION_LABELS[chart.visualization] ?? chart.visualization} · {metrics}
                    {chart.groupBy !== 'none' && ` by ${grouping.toLowerCase()}`}
                  </span>
                  <span className="cell-secondary show-sm">Updated {formatDateTime(chart.updatedAt, timeZone)}</span>
                </td>
                <td className="hide-sm nowrap">{VISUALIZATION_LABELS[chart.visualization] ?? chart.visualization}</td>
                <td className="hide-sm">{metrics}</td>
                <td className="hide-sm nowrap">{grouping}</td>
                <td className="hide-md nowrap">
                  <span className="cell-primary">{formatDateTime(chart.updatedAt, timeZone)}</span>
                  <span className="cell-secondary">
                    {chart.updatedBy ? `by ${chart.updatedBy} · ` : ''}revision {chart.revision}
                  </span>
                </td>
                {canManage && (
                  <td className="num">
                    {confirming === chart.id ? (
                      <span className="confirm-inline saved-row-actions" role="group" aria-label={`Delete ${chart.title}?`}>
                        <ChartUsage chartId={chart.id} href={href} compact />
                        <button
                          type="button"
                          className="button button-danger button-small"
                          disabled={busy !== null}
                          onClick={() => void remove(chart)}
                        >
                          {busy === chart.id ? 'Deleting…' : 'Delete'}
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
                          onClick={() => void duplicate(chart)}
                          aria-label={`Duplicate ${chart.title}`}
                        >
                          {busy === chart.id ? 'Duplicating…' : 'Duplicate'}
                        </button>
                        <button
                          type="button"
                          className="button button-secondary button-small"
                          disabled={busy !== null}
                          onClick={() => setConfirming(chart.id)}
                          aria-label={`Delete ${chart.title}`}
                        >
                          Delete
                        </button>
                      </span>
                    )}
                  </td>
                )}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

/** Charts in the anonymous public demo: they belong to a business, so sign in first. */
export function ChartsSignIn() {
  return (
    <div className="panel page-error">
      <h1 className="page-title">Charts need an account</h1>
      <ErrorState message="The demo shows the dashboard and reports; building and saving charts is for members of a business. Sign in or create an account to build your own." />
      <p className="page-error-action">
        <Link className="button button-primary" href={signInHref()}>
          Sign in
        </Link>
      </p>
    </div>
  )
}
