import type { DateRange } from '../../api/types'
import { formatDateRange, formatPercent } from '../../lib/format'
import { ArrowDownIcon, ArrowUpIcon } from '../Icons'

/** Period-over-period change as a compact pill; null means there was nothing to compare with. */
export function RevenueChange({ change, previousPeriod }: { change: number | null; previousPeriod: DateRange }) {
  const period = formatDateRange(previousPeriod.from, previousPeriod.to)
  if (change === null) {
    return (
      <span className="delta delta-neutral" title={`No revenue in the previous period (${period})`}>
        New
      </span>
    )
  }
  const tone = change > 0 ? 'up' : change < 0 ? 'down' : 'neutral'
  return (
    <span className={`delta delta-${tone}`} title={`Change in revenue vs ${period}`}>
      {tone === 'up' && <ArrowUpIcon />}
      {tone === 'down' && <ArrowDownIcon />}
      {formatPercent(change, { signed: true })}
    </span>
  )
}
