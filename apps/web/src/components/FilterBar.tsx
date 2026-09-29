import type { DateRange, StoreOption } from '../api/types'
import { RANGE_PRESETS, rangeForPreset, type Filters, type RangePreset } from '../lib/filters'

interface FilterBarProps {
  filters: Filters
  stores: StoreOption[]
  dataRange: DateRange | null
  onChange: (filters: Filters) => void
  /** Hide the store picker on pages that are already about one store (or all stores). */
  showStore?: boolean
}

export function FilterBar({ filters, stores, dataRange, onChange, showStore = true }: FilterBarProps) {
  const setPreset = (preset: RangePreset) => {
    // Switching to "custom" keeps the current window as the starting point.
    const range = rangeForPreset(preset, dataRange, { from: filters.from, to: filters.to })
    onChange({ ...filters, preset, ...range })
  }

  const setDate = (field: 'from' | 'to', value: string) => {
    if (!value) return
    const next = { ...filters, preset: 'custom' as const, [field]: value }
    // Keep the range valid while the user edits either end.
    if (next.from > next.to) {
      if (field === 'from') next.to = value
      else next.from = value
    }
    onChange(next)
  }

  return (
    <div className="filter-bar" role="group" aria-label="Dashboard filters">
      {showStore && (
      <label className="field">
        <span className="field-label">Store</span>
        <select
          className="control"
          value={filters.storeId ?? ''}
          onChange={(e) => onChange({ ...filters, storeId: e.target.value ? Number(e.target.value) : null })}
        >
          <option value="">All stores</option>
          {stores.map((store) => (
            <option key={store.id} value={store.id}>
              {store.name}
              {store.city ? ` · ${store.city}` : ''}
            </option>
          ))}
        </select>
      </label>
      )}

      <label className="field">
        <span className="field-label">Date range</span>
        <select className="control" value={filters.preset} onChange={(e) => setPreset(e.target.value as RangePreset)}>
          {RANGE_PRESETS.map((p) => (
            <option key={p.value} value={p.value}>
              {p.label}
            </option>
          ))}
        </select>
      </label>

      <div className="field-pair">
        <label className="field">
          <span className="field-label">From</span>
          <input
            type="date"
            className="control"
            value={filters.from}
            max={filters.to}
            onChange={(e) => setDate('from', e.target.value)}
          />
        </label>
        <label className="field">
          <span className="field-label">To</span>
          <input
            type="date"
            className="control"
            value={filters.to}
            min={filters.from}
            onChange={(e) => setDate('to', e.target.value)}
          />
        </label>
      </div>
    </div>
  )
}
