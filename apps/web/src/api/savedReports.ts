// Saved reports API (docs/saved-reports-contract.md §3). A saved report is a named report kind,
// store and date range of one business; its range is resolved to concrete dates (`period`) each
// time it is read or run, in the business time zone.
import { deleteJson, getJson, postJson, putJson } from './client'
import type { CategoryReport, ExportFormat, MonthlyReport, ReportKind } from './reports'
import type { DateRange } from './types'

/** Relative date ranges (contract §2), resolved against today in the business time zone. */
export type RelativePreset =
  | 'last_7_days'
  | 'last_30_days'
  | 'last_90_days'
  | 'last_365_days'
  | 'month_to_date'
  | 'previous_month'
  | 'last_3_months'
  | 'last_12_months'
  | 'quarter_to_date'
  | 'previous_quarter'
  | 'year_to_date'
  | 'previous_year'

/** Every preset in the order the pickers list them, with its label. */
export const RELATIVE_PRESETS: { value: RelativePreset; label: string }[] = [
  { value: 'last_7_days', label: 'Last 7 days' },
  { value: 'last_30_days', label: 'Last 30 days' },
  { value: 'last_90_days', label: 'Last 90 days' },
  { value: 'last_365_days', label: 'Last 365 days' },
  { value: 'month_to_date', label: 'Month to date' },
  { value: 'previous_month', label: 'Previous month' },
  { value: 'last_3_months', label: 'Last 3 whole months' },
  { value: 'last_12_months', label: 'Last 12 whole months' },
  { value: 'quarter_to_date', label: 'Quarter to date' },
  { value: 'previous_quarter', label: 'Previous quarter' },
  { value: 'year_to_date', label: 'Year to date' },
  { value: 'previous_year', label: 'Previous year' },
]

export const PRESET_LABELS = Object.fromEntries(RELATIVE_PRESETS.map((p) => [p.value, p.label])) as Record<
  RelativePreset,
  string
>

/** A range as sent: fixed dates, or a preset re-resolved every time the report runs. */
export type SavedReportRange = { type: 'fixed'; from: string; to: string } | { type: 'relative'; preset: RelativePreset }

/** A range as answered: the fields of the other type are null. */
export interface SavedReportRangeResponse {
  type: 'fixed' | 'relative'
  preset: RelativePreset | null
  from: string | null
  to: string | null
}

export interface SavedReport {
  id: number
  name: string
  kind: ReportKind
  range: SavedReportRangeResponse
  /** null: all stores. */
  storeId: number | null
  storeName: string | null
  /** The range resolved now, in the business time zone. */
  period: DateRange
  createdBy: string | null
  createdAt: string
  updatedAt: string
}

/** Body of POST and PUT; PUT replaces every field (a rename sends the other fields unchanged). */
export interface SavedReportInput {
  name: string
  kind: ReportKind
  range: SavedReportRange
  storeId: number | null
}

/** GET /{id}/report: the definition plus exactly one of the two reports, for the resolved period. */
export type SavedReportRun =
  | { savedReport: SavedReport; monthly: MonthlyReport; categories?: undefined }
  | { savedReport: SavedReport; categories: CategoryReport; monthly?: undefined }

/** Limits the server enforces (contract §1, §2); checked here too so mistakes show while typing. */
export const SAVED_REPORT_NAME_MAX = 120
export const MAX_RANGE_DAYS = 366 * 3

const base = '/api/saved-reports'

export const savedReportsApi = {
  list: (signal?: AbortSignal) => getJson<SavedReport[]>(base, {}, signal),

  get: (id: number, signal?: AbortSignal) => getJson<SavedReport>(`${base}/${id}`, {}, signal),

  create: (input: SavedReportInput) => postJson<SavedReport>(base, input),

  update: (id: number, input: SavedReportInput) => putJson<SavedReport>(`${base}/${id}`, input),

  remove: (id: number) => deleteJson(`${base}/${id}`),

  /** Runs the saved report for its range resolved now. */
  run: (id: number, signal?: AbortSignal) => getJson<SavedReportRun>(`${base}/${id}/report`, {}, signal),

  /** URL of the CSV or PDF download for the saved report (served as an attachment). */
  exportUrl: (id: number, format: ExportFormat): string => `${base}/${id}/report.${format}`,
}

/** The range of a saved report as a request body, e.g. to rename it without changing anything else. */
export function rangeInput(range: SavedReportRangeResponse): SavedReportRange {
  return range.type === 'relative'
    ? { type: 'relative', preset: range.preset! }
    : { type: 'fixed', from: range.from!, to: range.to! }
}

/** The request body that keeps a saved report as it is (edit one field on top of it). */
export function savedReportInput(report: SavedReport): SavedReportInput {
  return { name: report.name, kind: report.kind, range: rangeInput(report.range), storeId: report.storeId }
}
