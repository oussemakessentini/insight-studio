import type { DashboardFilter } from '../api/client'
import type { DashboardContext } from '../api/types'
import type { Filters } from '../lib/filters'

/** What every page receives from the workspace: business context and the shared store/date filters. */
export interface PageProps {
  context: DashboardContext
  filters: Filters
  onFiltersChange: (filters: Filters) => void
  /** Builds an in-app link that carries the current filters. */
  href: (path: string) => string
  /**
   * Re-fetches the business context (stores, data range, features), e.g. after an import adds
   * sales. Relative date presets then extend to the new data range without a page reload.
   */
  refreshContext: () => void
}

export function apiFilter(filters: Filters): DashboardFilter {
  return { from: filters.from, to: filters.to, storeId: filters.storeId }
}

/** Cache key for requests that depend on the shared filters. */
export function filterKey(filters: Filters): string {
  return `${filters.from}|${filters.to}|${filters.storeId ?? 'all'}`
}
