import { useState } from 'react'
import { ApiError } from '../api/client'
import { chartsApi, type Chart, type ChartDefinition, type ChartResult, type ChartRevisionSummary } from '../api/charts'
import type { StoreOption } from '../api/types'
import { ChartUsage } from '../components/charts/ChartUsage'
import { ChartPanel } from '../components/charts/ChartView'
import { FormError } from '../components/Form'
import { Link } from '../components/Link'
import { PageHeader } from '../components/PageHeader'
import { AsyncContent, ErrorState, Panel, Skeleton, SkeletonRows } from '../components/Panel'
import { useApi } from '../hooks/useApi'
import { useProductNames } from '../hooks/useProductNames'
import {
  chartPermissions,
  chartRangeDescription,
  chartRollingExplanation,
  chartShape,
  ENGINE_LABELS,
  GRANULARITY_LABELS,
  GROUP_BY_LABELS,
  isRanked,
  METRIC_LABELS,
  VISUALIZATION_LABELS,
} from '../lib/charts'
import { formatDateTimeLong, formatDateTime } from '../lib/format'
import { navigate, useSearch } from '../lib/router'
import { errorMessage } from '../lib/validation'
import '../styles/accounts.css'
import '../styles/reports.css'
import '../styles/charts.css'
import { VerifyToManageCharts } from './ChartsPage'
import type { PageProps } from './types'

/** `?revision=n` opens an older revision read-only; anything else is the current one. */
function revisionParam(search: string): number | null {
  const value = new URLSearchParams(search).get('revision')
  return value && /^\d+$/.test(value) && Number(value) > 0 ? Number(value) : null
}

/** One chart run with today's data, its definition and its revision history. */
export function ChartPage({ chartId, context, href }: PageProps & { chartId: number }) {
  const { business, stores } = context
  const can = chartPermissions(context.access)
  const requested = revisionParam(useSearch())
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [busy, setBusy] = useState<'duplicate' | 'delete' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const chart = useApi(`chart|${chartId}`, (signal) => chartsApi.get(chartId, signal))
  const revisions = useApi(`chart-revisions|${chartId}`, (signal) => chartsApi.revisions(chartId, signal))
  const listHref = href('/charts')
  const chartHref = href(`/charts/${chartId}`)

  // A requested revision loads alongside the chart; it is shown read-only unless it is the current one.
  const revision = useApi(`chart-revision|${chartId}|${requested ?? 'current'}`, (signal) =>
    requested === null ? Promise.resolve(null) : chartsApi.revision(chartId, requested, signal),
  )
  const data = useApi(`chart-data|${chartId}|${requested ?? 'current'}`, (signal) => chartsApi.data(chartId, requested, signal))
  const older = requested !== null && chart.data && requested !== chart.data.revision ? requested : null

  if (chart.error instanceof ApiError && chart.error.status === 404) return <ChartNotFound listHref={listHref} />
  if (chart.error) {
    return (
      <div className="panel page-error">
        <h1 className="page-title">This chart couldn’t be loaded</h1>
        <ErrorState message={chart.error.message} onRetry={chart.retry} />
      </div>
    )
  }

  const current = chart.data
  const definition: ChartDefinition | undefined = older === null ? current?.definition : (revision.data ?? undefined)?.definition

  const duplicate = async () => {
    setBusy('duplicate')
    setError(null)
    try {
      const copy = await chartsApi.duplicate(chartId)
      navigate(href(`/charts/${copy.id}`))
    } catch (err) {
      setError(errorMessage(err))
      setBusy(null)
    }
  }

  const remove = async () => {
    setBusy('delete')
    setError(null)
    try {
      await chartsApi.remove(chartId)
      navigate(listHref)
    } catch (err) {
      setError(errorMessage(err))
      setConfirmingDelete(false)
      setBusy(null)
    }
  }

  return (
    <>
      <PageHeader
        eyebrow={
          <nav aria-label="Breadcrumb" className="breadcrumb">
            <Link href={listHref}>Charts</Link>
          </nav>
        }
        title={definition ? <span className="break-anywhere">{definition.title}</span> : <Skeleton height={30} width={240} />}
        subtitle={
          definition && current ? (
            <ChartDetailsLine definition={definition} result={data.data} revision={older ?? current.revision} />
          ) : undefined
        }
      >
        {current && can.canManage && older === null && (
          <div className="saved-actions">
            {confirmingDelete ? (
              <span className="confirm-inline" role="group" aria-label={`Delete ${current.title}?`}>
                <span className="confirm-text">Delete this chart and its revisions?</span>
                <button type="button" className="button button-danger" disabled={busy !== null} onClick={() => void remove()}>
                  {busy === 'delete' ? 'Deleting…' : 'Delete'}
                </button>
                <button
                  type="button"
                  className="button button-secondary"
                  disabled={busy !== null}
                  onClick={() => setConfirmingDelete(false)}
                >
                  Cancel
                </button>
              </span>
            ) : (
              <>
                <Link className="button button-primary" href={href(`/charts/${chartId}/edit`)}>
                  Edit
                </Link>
                <button type="button" className="button button-secondary" disabled={busy !== null} onClick={() => void duplicate()}>
                  {busy === 'duplicate' ? 'Duplicating…' : 'Duplicate'}
                </button>
                <button type="button" className="button button-secondary" disabled={busy !== null} onClick={() => setConfirmingDelete(true)}>
                  Delete
                </button>
              </>
            )}
          </div>
        )}
      </PageHeader>

      {can.needsVerification && <VerifyToManageCharts />}
      {error && <FormError>{error}</FormError>}
      {confirmingDelete && current && (
        <div className="form-alert chart-delete-usage" role="status">
          <div>
            <strong>Before you delete it:</strong> <ChartUsage chartId={chartId} href={href} />
          </div>
        </div>
      )}

      {older !== null && current && (
        <div className="form-alert chart-revision-note" role="note">
          <div>
            You’re looking at <strong>revision {older}</strong>
            {revision.data ? ` saved ${formatDateTimeLong(revision.data.createdAt, business.timeZone)}${revision.data.createdBy ? ` by ${revision.data.createdBy}` : ''}` : ''}
            . It is read-only; the chart now uses revision {current.revision}.{' '}
            <Link className="form-link" href={chartHref}>
              See the current version
            </Link>
          </div>
        </div>
      )}

      {older !== null && revision.error ? (
        <div className="panel page-error">
          <ErrorState
            message={revision.error instanceof ApiError && revision.error.status === 404 ? `This chart has no revision ${older}.` : revision.error.message}
            onRetry={revision.error instanceof ApiError && revision.error.status === 404 ? undefined : revision.retry}
          />
        </div>
      ) : definition ? (
        <ChartPanel
          title={chartShape(definition)}
          subtitle={data.data ? resultSubtitle(data.data) : undefined}
          visualization={definition.visualization}
          {...data}
          errorHint={
            data.error instanceof ApiError && data.error.status === 400 ? (
              can.canManage && older === null ? (
                <>
                  The chart’s settings need a change before it can run.{' '}
                  <Link className="form-link" href={href(`/charts/${chartId}/edit`)}>
                    Edit the chart
                  </Link>
                </>
              ) : (
                'An owner or admin needs to update this chart before it can run.'
              )
            ) : undefined
          }
        />
      ) : (
        <Panel title="Chart">
          <Skeleton height={300} />
        </Panel>
      )}

      <div className="grid grid-halves">
        <Panel title="About this chart">
          {definition && current ? (
            <ChartFacts definition={definition} chart={current} stores={stores} timeZone={business.timeZone} older={older !== null} />
          ) : (
            <SkeletonRows rows={5} />
          )}
        </Panel>
        <Panel title="Revisions" subtitle="Every save keeps the previous version. Open one to see it as it was, read-only.">
          <AsyncContent {...revisions} skeleton={<SkeletonRows rows={3} />}>
            {(list) => (
              <RevisionList revisions={list} current={current?.revision ?? null} shown={older ?? current?.revision ?? null} chartHref={chartHref} timeZone={business.timeZone} />
            )}
          </AsyncContent>
        </Panel>
      </div>
    </>
  )
}

/** "Covers Jul 3 – Sep 30, 2026 · SQL engine · generated Oct 2, 8:00 AM" */
function resultSubtitle(result: ChartResult): string {
  return `Generated ${formatDateTime(result.generatedAt, result.timeZone)} · ${result.timeZone}`
}

/** "Bar chart · Last 90 days (rolling) · Jul 3 – Sep 30, 2026 · SQL · Revision 3" */
function ChartDetailsLine({ definition, result, revision }: { definition: ChartDefinition; result: ChartResult | undefined; revision: number }) {
  return (
    <span className="saved-details">
      <span>{VISUALIZATION_LABELS[definition.visualization] ?? definition.visualization}</span>
      <span>{chartRangeDescription(definition.range, result?.period)}</span>
      <span>{ENGINE_LABELS[result?.engine ?? definition.engine] ?? definition.engine} engine</span>
      <span>Revision {revision}</span>
    </span>
  )
}

function ChartFacts({
  definition,
  chart,
  stores,
  timeZone,
  older,
}: {
  definition: ChartDefinition
  chart: Chart
  stores: StoreOption[]
  timeZone: string
  older: boolean
}) {
  const productNames = useProductNames(definition.filters.productIds)
  const storeNames = definition.filters.storeIds.map((id) => stores.find((s) => s.id === id)?.name ?? `Store ${id}`)
  const productLabels = definition.filters.productIds.map((id) => productNames.get(id) ?? `Product ${id}`)
  const grouping =
    definition.groupBy === 'time'
      ? `Time, by ${(GRANULARITY_LABELS[definition.granularity ?? 'day'] ?? '').toLowerCase()}`
      : isRanked(definition.groupBy)
        ? `${GROUP_BY_LABELS[definition.groupBy]}, top ${definition.limit ?? 10}`
        : 'No grouping (totals)'

  return (
    <dl className="details-list chart-facts">
      <div>
        <dt>Metrics</dt>
        <dd>{definition.metrics.map((m) => METRIC_LABELS[m] ?? m).join(', ')}</dd>
      </div>
      <div>
        <dt>Grouped by</dt>
        <dd>{grouping}</dd>
      </div>
      <div>
        <dt>Dates</dt>
        <dd>
          {chartRangeDescription(definition.range)}
          {definition.range.type === 'relative' && <span className="cell-secondary">{chartRollingExplanation(timeZone)}</span>}
        </dd>
      </div>
      <div>
        <dt>Stores</dt>
        <dd className="break-anywhere">{storeNames.length ? storeNames.join(', ') : 'All stores'}</dd>
      </div>
      <div>
        <dt>Categories</dt>
        <dd className="break-anywhere">{definition.filters.categories.length ? definition.filters.categories.join(', ') : 'All categories'}</dd>
      </div>
      <div>
        <dt>Products</dt>
        <dd className="break-anywhere">{productLabels.length ? productLabels.join(', ') : 'All products'}</dd>
      </div>
      {!older && (
        <div>
          <dt>Last saved</dt>
          <dd>
            {formatDateTimeLong(chart.updatedAt, timeZone)}
            {chart.updatedBy && ` by ${chart.updatedBy}`}
            {chart.createdBy && <span className="cell-secondary">Created by {chart.createdBy}</span>}
          </dd>
        </div>
      )}
    </dl>
  )
}

function RevisionList({
  revisions,
  current,
  shown,
  chartHref,
  timeZone,
}: {
  revisions: ChartRevisionSummary[]
  current: number | null
  shown: number | null
  chartHref: string
  timeZone: string
}) {
  const withRevision = (n: number) => {
    const [path, query = ''] = chartHref.split('?')
    const params = new URLSearchParams(query)
    params.set('revision', String(n))
    return `${path}?${params}`
  }
  return (
    <ol className="chart-revisions">
      {revisions.map((r) => {
        const isCurrent = r.revision === current
        const isShown = r.revision === shown
        return (
          <li key={r.revision} className={isShown ? 'is-shown' : undefined}>
            <span className="chart-revision-main">
              {isShown ? (
                <strong aria-current="page">Revision {r.revision}</strong>
              ) : (
                <Link className="cell-link" href={isCurrent ? chartHref : withRevision(r.revision)}>
                  Revision {r.revision}
                </Link>
              )}
              {isCurrent && <span className="chip">Current</span>}
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

function ChartNotFound({ listHref }: { listHref: string }) {
  return (
    <div className="panel page-error">
      <h1 className="page-title">Chart not found</h1>
      <ErrorState message="It may have been deleted, or it belongs to another business." />
      <p className="page-error-action">
        <Link className="button button-secondary" href={listHref}>
          See all charts
        </Link>
      </p>
    </div>
  )
}
