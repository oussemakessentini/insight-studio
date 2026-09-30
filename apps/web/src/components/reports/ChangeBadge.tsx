import { formatPercent } from '../../lib/format'

/** Month-over-month revenue change; a dash when there is no baseline. */
export function ChangeBadge({ value, title }: { value: number | null; title?: string }) {
  if (value === null) {
    return (
      <span className="text-muted" title={title ?? 'No revenue in the previous month to compare with'}>
        {'—'}
      </span>
    )
  }
  const tone = value > 0 ? 'up' : value < 0 ? 'down' : 'neutral'
  return (
    <span className={`delta delta-${tone} reports-change`} title={title}>
      {formatPercent(value, { signed: true })}
    </span>
  )
}
