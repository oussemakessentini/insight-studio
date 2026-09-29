import type { DateRange } from '../api/types'
import { addDays, toIsoDate } from './format'

export type RangePreset = '7d' | '30d' | '90d' | 'all' | 'custom'

export const RANGE_PRESETS: { value: RangePreset; label: string }[] = [
  { value: '7d', label: 'Last 7 days' },
  { value: '30d', label: 'Last 30 days' },
  { value: '90d', label: 'Last 90 days' },
  { value: 'all', label: 'All data' },
  { value: 'custom', label: 'Custom range' },
]

export const DEFAULT_PRESET: RangePreset = '90d'

export interface Filters {
  preset: RangePreset
  from: string
  to: string
  storeId: number | null
}

const PRESET_DAYS: Partial<Record<RangePreset, number>> = { '7d': 7, '30d': 30, '90d': 90 }

/**
 * Relative presets end on the last day with data rather than today, so a historical dataset
 * (like the demo) still opens on a meaningful window. With live data the two coincide.
 */
export function rangeForPreset(preset: RangePreset, dataRange: DateRange | null, fallback?: DateRange): DateRange {
  const end = dataRange?.to ?? toIsoDate(new Date())
  if (preset === 'all' && dataRange) return dataRange
  if (preset === 'custom' && fallback) return fallback
  const days = PRESET_DAYS[preset] ?? PRESET_DAYS[DEFAULT_PRESET]!
  return { from: addDays(end, -(days - 1)), to: end }
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

/** Reads filters from the page URL, falling back to defaults for anything missing or invalid. */
export function filtersFromUrl(dataRange: DateRange | null, storeIds: number[]): Filters {
  const params = new URLSearchParams(window.location.search)
  const presetParam = params.get('range') as RangePreset | null
  const preset = RANGE_PRESETS.some((p) => p.value === presetParam) ? presetParam! : DEFAULT_PRESET

  const storeParam = Number(params.get('store'))
  const storeId = storeIds.includes(storeParam) ? storeParam : null

  if (preset === 'custom') {
    const from = params.get('from') ?? ''
    const to = params.get('to') ?? ''
    if (ISO_DATE.test(from) && ISO_DATE.test(to) && from <= to) {
      return { preset, from, to, storeId }
    }
    return { preset: DEFAULT_PRESET, ...rangeForPreset(DEFAULT_PRESET, dataRange), storeId }
  }
  return { preset, ...rangeForPreset(preset, dataRange), storeId }
}

export function writeFiltersToUrl(filters: Filters): void {
  const params = new URLSearchParams()
  if (filters.preset !== DEFAULT_PRESET) params.set('range', filters.preset)
  if (filters.preset === 'custom') {
    params.set('from', filters.from)
    params.set('to', filters.to)
  }
  if (filters.storeId !== null) params.set('store', String(filters.storeId))
  const query = params.toString()
  const url = `${window.location.pathname}${query ? `?${query}` : ''}`
  window.history.replaceState(null, '', url)
}
