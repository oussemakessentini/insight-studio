// Reports API (com.oussamaksantini.insightstudio.report). Dates are ISO `yyyy-MM-dd` strings in the
// business time zone; money values are numbers with two decimals, percentages with one.
import { getJson, type DashboardFilter } from './client'
import type { DateRange } from './types'

export type ReportKind = 'monthly' | 'categories'

export interface MonthlyReportRow {
  /** First day of the calendar month; may precede the requested period. */
  month: string
  revenue: number
  orders: number
  unitsSold: number
  averageOrderValue: number
  /** Change from the previous row; null for the first row or when the previous month had no revenue. */
  revenueChangePercent: number | null
  daysCovered: number
  daysInMonth: number
  /** False when only part of the month lies inside the requested period. */
  complete: boolean
}

export interface MonthlyReport {
  period: DateRange
  storeId: number | null
  rows: MonthlyReportRow[]
  totals: { revenue: number; orders: number; unitsSold: number; averageOrderValue: number }
}

export interface CategoryReportRow {
  category: string
  revenue: number
  unitsSold: number
  /** Orders containing this category; a receipt spanning categories counts once in each. */
  orders: number
  revenueSharePercent: number
  averageUnitPrice: number
}

export interface CategoryReport {
  period: DateRange
  storeId: number | null
  rows: CategoryReportRow[]
  /** `orders` counts distinct orders across all categories. */
  totals: { revenue: number; unitsSold: number; orders: number; averageOrderValue: number; averageUnitPrice: number }
}

const base = '/api/reports'

export const reportsApi = {
  monthly: (f: DashboardFilter, signal?: AbortSignal) => getJson<MonthlyReport>(`${base}/monthly`, { ...f }, signal),

  categories: (f: DashboardFilter, signal?: AbortSignal) =>
    getJson<CategoryReport>(`${base}/categories`, { ...f }, signal),

  /** URL of the CSV download for a report with the given filters (served as an attachment). */
  csvUrl: (kind: ReportKind, f: DashboardFilter): string => {
    const params = new URLSearchParams({ from: f.from, to: f.to })
    if (f.storeId !== null) params.set('storeId', String(f.storeId))
    return `${base}/${kind}.csv?${params}`
  },
}
