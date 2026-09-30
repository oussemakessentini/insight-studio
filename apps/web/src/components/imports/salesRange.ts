import type { MouseEvent } from 'react'
import { withFilters, type Filters } from '../../lib/filters'

const dateFormatters = new Map<string, Intl.DateTimeFormat>()

/** The calendar date (yyyy-MM-dd) of `instant` in `timeZone`. */
export function localIsoDate(instant: string, timeZone: string): string {
  let formatter = dateFormatters.get(timeZone)
  if (!formatter) {
    // en-CA formats dates as yyyy-MM-dd.
    formatter = new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone })
    dateFormatters.set(timeZone, formatter)
  }
  return formatter.format(new Date(instant))
}

/**
 * A link to the Sales page limited to the dates an import covers (all stores). In-app links don't
 * change the shared filters by themselves, so a plain click also applies them to the workspace;
 * modified clicks (new tab) just open the URL, which carries the same filters.
 */
export function importedSalesLink(
  firstSoldAt: string,
  lastSoldAt: string,
  timeZone: string,
  onFiltersChange: (filters: Filters) => void,
) {
  const filters: Filters = {
    preset: 'custom',
    from: localIsoDate(firstSoldAt, timeZone),
    to: localIsoDate(lastSoldAt, timeZone),
    storeId: null,
  }
  return {
    href: withFilters('/sales', filters),
    onClick: (event: MouseEvent<HTMLAnchorElement>) => {
      if (event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) {
        onFiltersChange(filters)
      }
    },
  }
}
