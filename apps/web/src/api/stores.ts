// Store performance API (apps/api com.oussamaksantini.insightstudio.store).
// The global store filter does not apply here: the store is part of the path.
import { getJson } from './client'
import type { DateRange, Granularity, MetricValue, RevenueSeries, TopProductsResponse } from './types'

/** Inclusive ISO dates in the business time zone. */
export interface StoreWindow {
  from: string
  to: string
}

export interface StorePerformance {
  storeId: number
  code: string
  name: string
  city: string | null
  revenue: number
  orders: number
  unitsSold: number
  averageOrderValue: number
  /** Share of the revenue of all stores in the period. */
  revenueSharePercent: number
  /** Versus the previous period of equal length; null when the store had no revenue then. */
  revenueChangePercent: number | null
}

export interface StoreListResponse {
  period: DateRange
  previousPeriod: DateRange
  /** Every store, including those without orders; highest revenue first, then name. */
  stores: StorePerformance[]
}

export interface StoreInfo {
  id: number
  code: string
  name: string
  city: string | null
}

export interface CategorySales {
  category: string
  revenue: number
  unitsSold: number
  /** Receipts containing the category; a receipt spanning categories counts in each. */
  orders: number
  /** Share of the store's revenue. */
  revenueSharePercent: number
}

export interface StoreDetail {
  store: StoreInfo
  period: DateRange
  previousPeriod: DateRange
  revenue: MetricValue
  orders: MetricValue
  unitsSold: MetricValue
  averageOrderValue: MetricValue
  /** Highest revenue first. */
  categories: CategorySales[]
}

const base = '/api/stores'

export const storesApi = {
  list: (w: StoreWindow, signal?: AbortSignal) => getJson<StoreListResponse>(base, { from: w.from, to: w.to }, signal),

  detail: (storeId: number, w: StoreWindow, signal?: AbortSignal) =>
    getJson<StoreDetail>(`${base}/${storeId}`, { from: w.from, to: w.to }, signal),

  revenue: (storeId: number, w: StoreWindow, granularity: Granularity | null, signal?: AbortSignal) =>
    getJson<RevenueSeries>(`${base}/${storeId}/revenue`, { from: w.from, to: w.to, granularity }, signal),

  topProducts: (storeId: number, w: StoreWindow, limit: number, signal?: AbortSignal) =>
    getJson<TopProductsResponse>(`${base}/${storeId}/top-products`, { from: w.from, to: w.to, limit }, signal),
}
