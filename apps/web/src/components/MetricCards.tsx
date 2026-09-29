import type { DateRange, NullableMetricValue } from '../api/types'
import type { ApiState } from '../hooks/useApi'
import { formatDateRange, formatPercent } from '../lib/format'
import { ArrowDownIcon, ArrowUpIcon } from './Icons'
import { ErrorState, Skeleton } from './Panel'

export interface MetricCardSpec<T> {
  label: string
  pick: (data: T) => NullableMetricValue
  format: (value: number) => string
  /** Optional muted line under the value, e.g. the current list price. */
  caption?: (data: T) => string
}

interface MetricGridProps<T> {
  state: ApiState<T>
  cards: MetricCardSpec<T>[]
  previousPeriod: (data: T) => DateRange
}

/** A row of metric cards, each comparing the period with the previous one. */
export function MetricGrid<T>({ state, cards, previousPeriod }: MetricGridProps<T>) {
  const { data, error, loading, retry } = state

  if (error) {
    return (
      <div className="panel metric-error">
        <ErrorState message={error.message} onRetry={retry} />
      </div>
    )
  }

  return (
    <div className={`metric-grid ${loading && data ? 'is-refreshing' : ''}`} aria-busy={loading}>
      {cards.map(({ label, pick, format, caption }) => (
        <article key={label} className="metric-card">
          <h3 className="metric-label">{label}</h3>
          {data ? (
            <>
              <p className="metric-value">{pick(data).value === null ? '—' : format(pick(data).value!)}</p>
              <Delta metric={pick(data)} format={format} previousPeriod={previousPeriod(data)} />
              {caption && <p className="metric-caption">{caption(data)}</p>}
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

function Delta({
  metric,
  format,
  previousPeriod,
}: {
  metric: NullableMetricValue
  format: (v: number) => string
  previousPeriod: DateRange
}) {
  const previous = metric.previousValue === null ? 'no sales' : format(metric.previousValue)
  const tooltip = `Previous period (${formatDateRange(previousPeriod.from, previousPeriod.to)}): ${previous}`
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
