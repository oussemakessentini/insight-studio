import { useState } from 'react'
import type { ChartSummary } from '../../api/charts'
import type { ApiState } from '../../hooks/useApi'
import { chartShape, VISUALIZATION_LABELS } from '../../lib/charts'
import { Dialog } from '../Dialog'
import { TextField } from '../Form'
import { Link } from '../Link'
import { AsyncContent, EmptyState, SkeletonRows } from '../Panel'

interface ChartPickerProps {
  open: boolean
  charts: ApiState<ChartSummary[]>
  /** Charts already on the dashboard (they can be added again, e.g. at another size). */
  placed: Set<number>
  /** Why nothing more can be added (24 charts, no room), or null. */
  full: string | null
  newChartHref: string
  onPick: (chart: ChartSummary) => void
  onClose: () => void
}

/** The business's saved charts, to add one to the dashboard. */
export function ChartPicker({ open, charts, placed, full, newChartHref, onPick, onClose }: ChartPickerProps) {
  return (
    <Dialog open={open} title="Add a chart" onClose={onClose}>
      <PickerBody charts={charts} placed={placed} full={full} newChartHref={newChartHref} onPick={onPick} />
    </Dialog>
  )
}

function PickerBody({ charts, placed, full, newChartHref, onPick }: Omit<ChartPickerProps, 'open' | 'onClose'>) {
  const [query, setQuery] = useState('')
  return (
    <div className="form-stack">
      <p className="dialog-text">
        The chart goes in the first free spot of both the desktop and the mobile layout; move it from there. It always shows the
        chart’s current settings.
      </p>
      {full && <p className="form-error">{full}</p>}
      <TextField label="Search charts" value={query} onChange={setQuery} type="search" autoComplete="off" />
      <AsyncContent {...charts} skeleton={<SkeletonRows rows={4} />}>
        {(list) => {
          const needle = query.trim().toLowerCase()
          const shown = needle ? list.filter((c) => c.title.toLowerCase().includes(needle)) : list
          if (list.length === 0) {
            return (
              <div className="empty-with-action">
                <EmptyState message="There are no saved charts yet. Build one first, then add it here." />
                <Link className="button button-secondary" href={newChartHref}>
                  New chart
                </Link>
              </div>
            )
          }
          if (shown.length === 0) return <EmptyState message={`No chart title contains “${query.trim()}”.`} />
          return (
            <ul className="dashboard-picker">
              {shown.map((chart) => (
                <li key={chart.id}>
                  <button
                    type="button"
                    className="dashboard-picker-item"
                    disabled={full !== null}
                    onClick={() => onPick(chart)}
                    aria-label={`Add ${chart.title}`}
                  >
                    <span className="dashboard-picker-title break-anywhere">{chart.title}</span>
                    <span className="cell-secondary">
                      {VISUALIZATION_LABELS[chart.visualization] ?? chart.visualization} · {chartShape(chart)}
                      {placed.has(chart.id) && ' · already on this dashboard'}
                    </span>
                    <span className="dashboard-picker-action" aria-hidden="true">
                      Add
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )
        }}
      </AsyncContent>
    </div>
  )
}
