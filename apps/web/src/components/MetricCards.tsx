import type { MetricValue, Summary } from '../api/types'
import type { ApiState } from '../hooks/useApi'
import { formatCurrency, formatDateRange, formatNumber, formatPercent } from '../lib/format'
import { ArrowDownIcon, ArrowUpIcon } from './Icons'
import { ErrorState, Skeleton } from './Panel'

interface MetricCardsProps {
  state: ApiState<Summary>
  currency: string
}

export function MetricCards({ state, currency }: MetricCardsProps) {
  const { data, error, loading, retry } = state

  if (error) {
    return (
      <div className="panel metric-error">
        <ErrorState message={error.message} onRetry={retry} />
      </div>
    )
  }

  const money = (v: number) => formatCurrency(v, currency)
  const cards: { label: string; pick: (s: Summary) => MetricValue; format: (v: number) => string }[] = [
    { label: 'Revenue', pick: (s) => s.revenue, format: money },
    { label: 'Orders', pick: (s) => s.orders, format: formatNumber },
    { label: 'Average order value', pick: (s) => s.averageOrderValue, format: money },
    { label: 'Units sold', pick: (s) => s.unitsSold, format: formatNumber },
  ]

  return (
    <div className={`metric-grid ${loading && data ? 'is-refreshing' : ''}`} aria-busy={loading}>
      {cards.map(({ label, pick, format }) => (
        <article key={label} className="metric-card">
          <h3 className="metric-label">{label}</h3>
          {data ? (
            <>
              <p className="metric-value">{format(pick(data).value)}</p>
              <Delta metric={pick(data)} format={format} previousPeriod={formatDateRange(data.previousPeriod.from, data.previousPeriod.to)} />
            </>
          ) : (
            <>
              <Skeleton height={32} width="70%" />
              <Skeleton height={14} width="55%" />
            </>
          )}
        </article>
      ))}
    </div>
  )
}

function Delta({ metric, format, previousPeriod }: { metric: MetricValue; format: (v: number) => string; previousPeriod: string }) {
  const tooltip = `Previous period (${previousPeriod}): ${format(metric.previousValue)}`
  if (metric.changePercent === null) {
    return (
      <p className="metric-delta" title={tooltip}>
        <span className="delta delta-neutral">No prior data</span>
      </p>
    )
  }
  const change = metric.changePercent
  const tone = change > 0 ? 'up' : change < 0 ? 'down' : 'neutral'
  return (
    <p className="metric-delta" title={tooltip}>
      <span className={`delta delta-${tone}`}>
        {tone === 'up' && <ArrowUpIcon />}
        {tone === 'down' && <ArrowDownIcon />}
        {formatPercent(change, { signed: true })}
      </span>
      <span className="delta-caption">vs previous period</span>
    </p>
  )
}
