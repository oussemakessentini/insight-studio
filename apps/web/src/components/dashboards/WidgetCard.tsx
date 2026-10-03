import { useId, useState, type ReactNode } from 'react'
import { chartsApi } from '../../api/charts'
import { ApiError } from '../../api/client'
import type { DashboardWidget, DashboardWidgetChart } from '../../api/dashboards'
import { useApi } from '../../hooks/useApi'
import { useElementHeight } from '../../hooks/useElementHeight'
import { VISUALIZATION_LABELS } from '../../lib/charts'
import { chartDataQueue } from '../../lib/requestQueue'
import { ChartBody } from '../charts/ChartView'
import { Link } from '../Link'
import { ErrorState, Skeleton } from '../Panel'
import { Segmented } from '../TrendChart'

interface WidgetCardProps {
  widget: DashboardWidget
  /** Changes when the dashboard is refreshed: every widget runs its chart again. */
  refreshKey: number
  canManage: boolean
  href: (path: string) => string
  /** Where owners and admins remove deleted charts; null on a read-only revision. */
  editHref: string | null
}

/** One widget of a dashboard: its chart run with today's data, or a placeholder once the chart was deleted. */
export function WidgetCard({ widget, ...props }: WidgetCardProps) {
  if (widget.missing || widget.chart === null) return <MissingWidget canManage={props.canManage} editHref={props.editHref} />
  return <ChartWidget chart={widget.chart} widgetId={widget.id} {...props} />
}

function ChartWidget({
  chart,
  widgetId,
  refreshKey,
  canManage,
  href,
  editHref,
}: Omit<WidgetCardProps, 'widget'> & { chart: DashboardWidgetChart; widgetId: string }) {
  const titleId = useId()
  const [view, setView] = useState<'chart' | 'table'>('chart')
  const [bodyRef, bodyHeight] = useElementHeight<HTMLDivElement>()
  // Each widget loads, fails and retries on its own; the queue keeps at most 3 chart runs in flight.
  const data = useApi(`widget-data|${widgetId}|${chart.id}|${refreshKey}`, (signal) =>
    chartDataQueue.run((s) => chartsApi.data(chart.id, null, s), signal),
  )
  const hasChartView = chart.visualization !== 'table'

  // The chart was deleted after the dashboard loaded.
  if (data.error instanceof ApiError && data.error.status === 404) return <MissingWidget canManage={canManage} editHref={editHref} />

  let body: ReactNode
  if (data.error) {
    body = (
      <>
        <ErrorState message={data.error.message} onRetry={data.retry} />
        {data.error instanceof ApiError && data.error.status === 400 && (
          <p className="chart-error-hint">
            {canManage ? (
              <>
                The chart’s settings need a change before it can run.{' '}
                <Link className="form-link" href={href(`/charts/${chart.id}/edit`)}>
                  Edit the chart
                </Link>
              </>
            ) : (
              'An owner or admin needs to update this chart before it can run.'
            )}
          </p>
        )}
      </>
    )
  } else if (data.data === undefined) {
    body = (
      <>
        <span className="visually-hidden">Loading {chart.title}</span>
        <Skeleton height={Math.max(48, (bodyHeight ?? 120) - 4)} />
      </>
    )
  } else {
    body = (
      <div className={data.loading ? 'is-refreshing' : undefined} aria-busy={data.loading}>
        <ChartBody result={data.data} visualization={chart.visualization} view={hasChartView ? view : 'table'} height={bodyHeight} />
      </div>
    )
  }

  return (
    <article className="dashboard-widget" aria-labelledby={titleId} aria-busy={data.loading}>
      <header className="dashboard-widget-header">
        <h2 className="dashboard-widget-title" id={titleId}>
          <Link className="cell-link" href={href(`/charts/${chart.id}`)} title={`${chart.title} · ${VISUALIZATION_LABELS[chart.visualization] ?? ''}`}>
            {chart.title}
          </Link>
        </h2>
        {hasChartView && data.data !== undefined && !data.error && (
          <Segmented
            label={`${chart.title}: view`}
            options={[
              { value: 'chart', label: 'Chart' },
              { value: 'table', label: 'Table' },
            ]}
            value={view}
            onChange={setView}
          />
        )}
      </header>
      <div className="dashboard-widget-body" ref={bodyRef}>
        {body}
      </div>
    </article>
  )
}

/** A widget whose chart was deleted (contract §3): the layout keeps it until an owner or admin removes it. */
export function MissingWidget({ canManage, editHref, actions }: { canManage: boolean; editHref: string | null; actions?: ReactNode }) {
  const titleId = useId()
  return (
    <article className="dashboard-widget dashboard-widget-missing" aria-labelledby={titleId}>
      <header className="dashboard-widget-header">
        <h2 className="dashboard-widget-title" id={titleId}>
          This chart was deleted
        </h2>
      </header>
      <div className="dashboard-widget-body">
        <p className="dashboard-missing-text">
          {actions
            ? 'Remove it before saving: a dashboard can only be saved without deleted charts.'
            : canManage && editHref
              ? 'Its place stays empty until it is removed from the dashboard. '
              : 'Its place stays empty until an owner or admin removes it from the dashboard.'}
          {!actions && canManage && editHref && (
            <Link className="form-link" href={editHref}>
              Edit the dashboard
            </Link>
          )}
        </p>
        {actions}
      </div>
    </article>
  )
}
