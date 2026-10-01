import type { ReportKind } from '../api/reports'
import { PRESET_LABELS, type SavedReport, type SavedReportRangeResponse } from '../api/savedReports'
import type { BusinessAccess } from '../api/types'
import { formatDateRange } from './format'

export const KIND_LABELS: Record<ReportKind, string> = {
  monthly: 'Monthly report',
  categories: 'Category report',
}

/** "Previous quarter (rolling)", or "Fixed dates" for a range that never moves. */
export function rangeLabel(range: SavedReportRangeResponse): string {
  if (range.type === 'fixed') return 'Fixed dates'
  return `${range.preset ? (PRESET_LABELS[range.preset] ?? range.preset) : 'Relative dates'} (rolling)`
}

/** How a rolling period is worked out; the API resolves it the same way (contract §2). */
export function rollingExplanation(timeZone: string): string {
  return `Rolling periods are recalculated from today’s date in ${timeZone} every time the report runs.`
}

/** "Last 30 days · Sep 2 – Oct 1, 2026": how the range is defined, and the dates it covers today. */
export function rangeDescription(report: Pick<SavedReport, 'range' | 'period'>): string {
  return `${rangeLabel(report.range)} · ${formatDateRange(report.period.from, report.period.to)}`
}

export function storeLabel(report: Pick<SavedReport, 'storeId' | 'storeName'>): string {
  if (report.storeId === null) return 'All stores'
  return report.storeName ?? `Store ${report.storeId}`
}

export interface SavedReportPermissions {
  /** Saved reports exist only for members: not in the anonymous public demo (the API answers 401). */
  available: boolean
  /** Create, edit and delete: verified OWNER or ADMIN (contract §3, `require(Role.ADMIN)`). */
  canManage: boolean
  /** An OWNER or ADMIN who could manage saved reports once their email address is verified. */
  needsVerification: boolean
}

/**
 * What the current user may do with saved reports. The API enforces the same rules; this only
 * decides which controls to show. Derived from the role rather than `canManageCatalog`, so it keeps
 * following the saved-reports rule if the catalogue permission ever changes.
 */
export function savedReportPermissions(access: BusinessAccess): SavedReportPermissions {
  const admin = access.role === 'OWNER' || access.role === 'ADMIN'
  return {
    available: access.role !== 'DEMO',
    canManage: admin && access.emailVerified && !access.readOnly,
    needsVerification: admin && !access.emailVerified,
  }
}
