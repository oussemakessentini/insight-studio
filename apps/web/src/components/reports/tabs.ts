import type { ReportKind } from '../../api/reports'

export const REPORT_TABS: { value: ReportKind; label: string }[] = [
  { value: 'monthly', label: 'Monthly' },
  { value: 'categories', label: 'Categories' },
]

export const DEFAULT_REPORT: ReportKind = 'monthly'

export const tabId = (kind: ReportKind) => `reports-tab-${kind}`
export const panelId = (kind: ReportKind) => `reports-panel-${kind}`

/** The report selected by `?report=`, falling back to the default for a missing or unknown value. */
export function reportFromUrl(): ReportKind {
  const value = new URLSearchParams(window.location.search).get('report')
  return REPORT_TABS.find((t) => t.value === value)?.value ?? DEFAULT_REPORT
}
