import { Fragment } from 'react'
import { chartsApi } from '../../api/charts'
import { useApi } from '../../hooks/useApi'
import { Link } from '../Link'

/**
 * Which dashboards show a chart, said before it is deleted (contract §3): deleting is allowed, but
 * those dashboards then show "This chart was deleted" until an owner or admin removes it.
 * `compact` is one short line for a table row.
 */
export function ChartUsage({ chartId, href, compact = false }: { chartId: number; href: (path: string) => string; compact?: boolean }) {
  const usage = useApi(`chart-dashboards|${chartId}`, (signal) => chartsApi.dashboards(chartId, signal))

  if (usage.error) {
    return (
      <span className="chart-usage">
        Couldn’t check which dashboards show it ({usage.error.message}).{' '}
        <button type="button" className="link-button form-link" onClick={usage.retry}>
          Try again
        </button>
      </span>
    )
  }
  if (!usage.data) return <span className="chart-usage">Checking which dashboards show it…</span>

  const dashboards = usage.data
  if (dashboards.length === 0) return <span className="chart-usage">No dashboard shows it.</span>
  if (compact) {
    const names = dashboards.map((d) => d.name).join(', ')
    return (
      <span className="chart-usage chart-usage-warning" title={names}>
        On {dashboards.length === 1 ? `“${dashboards[0].name}”` : `${dashboards.length} dashboards`}
        {dashboards.length > 1 && <span className="visually-hidden">: {names}</span>}
      </span>
    )
  }
  return (
    <span className="chart-usage">
      {dashboards.length === 1 ? 'It is on the dashboard ' : `It is on ${dashboards.length} dashboards: `}
      {dashboards.map((d, i) => (
        <Fragment key={d.id}>
          {i > 0 && ', '}
          <Link className="form-link" href={href(`/dashboards/${d.id}`)}>
            {d.name}
          </Link>
        </Fragment>
      ))}
      . {dashboards.length === 1 ? 'It' : 'Each'} will show “This chart was deleted” in its place until an owner or admin removes it.
    </span>
  )
}
