// Chart builder API (docs/chart-builder-contract.md §1, §4). A chart is a validated definition
// (visualization, metrics, grouping, dates, filters) stored with revisions; running it answers a
// ChartResult computed for its dates resolved today in the business time zone.
import { deleteJson, getJson, postJson, putJson } from './client'
import type { RelativePreset } from './savedReports'
import type { DateRange, Granularity } from './types'

export type ChartVisualization = 'kpi' | 'line' | 'bar' | 'pie' | 'table'
export type ChartMetric = 'revenue' | 'orders' | 'units' | 'average_order_value'
export type ChartGroupBy = 'none' | 'time' | 'store' | 'product' | 'category'
export type ChartEngine = 'sql' | 'cube'
export type MetricUnit = 'money' | 'count'

/** Dates as sent and stored: fixed dates, or a preset re-resolved every time the chart runs. */
export type ChartRange = { type: 'fixed'; from: string; to: string } | { type: 'relative'; preset: RelativePreset }

export interface ChartFilters {
  storeIds: number[]
  categories: string[]
  productIds: number[]
}

/** A chart definition (`schemaVersion: 1`). It never carries layout: dashboards come later. */
export interface ChartDefinition {
  schemaVersion: 1
  title: string
  visualization: ChartVisualization
  metrics: ChartMetric[]
  groupBy: ChartGroupBy
  /** Only when grouped by time. */
  granularity: Granularity | null
  range: ChartRange
  filters: ChartFilters
  /** Groups shown when grouped by store, product or category (top groups by the first metric). */
  limit: number | null
  engine: ChartEngine
}

export interface Chart {
  id: number
  title: string
  revision: number
  definition: ChartDefinition
  createdBy: string | null
  updatedBy: string | null
  createdAt: string
  updatedAt: string
}

export interface ChartSummary {
  id: number
  title: string
  visualization: ChartVisualization
  metrics: ChartMetric[]
  groupBy: ChartGroupBy
  revision: number
  updatedBy: string | null
  updatedAt: string
}

export interface ChartRevisionSummary {
  revision: number
  createdBy: string | null
  createdAt: string
}

export interface ChartRevision extends ChartRevisionSummary {
  definition: ChartDefinition
}

// ---- Catalogue: everything the builder may offer -----------------------------------------------

export interface CatalogMetric {
  key: ChartMetric
  label: string
  unit: MetricUnit
  /** Whether groups add up to the total (only additive metrics can be a pie). */
  additive: boolean
}

export interface CatalogDimension {
  key: ChartGroupBy
  label: string
  /** For `time`: the bucket sizes offered. */
  granularities?: Granularity[]
}

export interface CatalogVisualization {
  key: ChartVisualization
  label: string
  /** Groupings this visualization accepts. */
  groupBy: ChartGroupBy[]
  minMetrics: number
  maxMetrics: number
}

/**
 * A combination the server refuses, with the reason to show. A rule applies when every field it
 * names matches; fields it leaves out match anything.
 */
export interface CatalogRule {
  visualization?: ChartVisualization
  groupBy?: ChartGroupBy
  metric?: ChartMetric
  allowed: false
  reason: string
}

/** A filter option as the catalogue lists it; the UI also accepts `{id, name}` and plain strings. */
export type CatalogFilterOption = { value: string | number; label: string } | { id: number; name: string } | string

export interface CatalogFilter {
  /** `storeIds`, `categories` or `productIds`. */
  key: string
  label: string
  /** This business's stores and categories; absent for products (searched instead). */
  options?: CatalogFilterOption[]
}

export interface ChartCatalog {
  metrics: CatalogMetric[]
  dimensions: CatalogDimension[]
  visualizations: CatalogVisualization[]
  rules: CatalogRule[]
  presets: { key: RelativePreset; label: string }[]
  filters: CatalogFilter[]
  /** Numeric limits (e.g. the maximum `limit`, range days); see `lib/charts.ts` for the defaults. */
  limits: Record<string, number | undefined>
  engines: ChartEngine[]
  defaultEngine: ChartEngine
}

// ---- Results -----------------------------------------------------------------------------------

export interface ChartColumn {
  /** `group` for the dimension column, otherwise the metric key. */
  key: string
  label: string
  type: 'dimension' | 'metric'
  unit?: MetricUnit
}

export interface ChartRow {
  /** Bucket start (ISO date) for time rows, the group id otherwise, `total` for a KPI. */
  key: string
  label: string
  values: Partial<Record<ChartMetric, number>>
  /** An edge time bucket only partly inside the period. */
  partial: boolean
}

export interface ChartResult {
  period: DateRange
  timeZone: string
  currency: string
  engine: ChartEngine
  groupBy: ChartGroupBy
  granularity: Granularity | null
  columns: ChartColumn[]
  rows: ChartRow[]
  /** Over the whole filtered period, not only the groups shown. */
  totals: Partial<Record<ChartMetric, number>>
  /** More groups exist than `limit`. */
  truncated: boolean
  totalGroups: number | null
  generatedAt: string
}

/** Body of a duplicate: the server picks "Copy of …" (made unique) without a title. */
export interface DuplicateInput {
  title?: string
}

const base = '/api/charts'

export const chartsApi = {
  catalog: (signal?: AbortSignal) => getJson<ChartCatalog>(`${base}/catalog`, {}, signal),

  /** Runs an unsaved definition (ADMIN+). */
  preview: (definition: ChartDefinition, signal?: AbortSignal) =>
    postJson<ChartResult>(`${base}/preview`, definition, { signal }),

  list: (signal?: AbortSignal) => getJson<ChartSummary[]>(base, {}, signal),

  get: (id: number, signal?: AbortSignal) => getJson<Chart>(`${base}/${id}`, {}, signal),

  create: (definition: ChartDefinition) => postJson<Chart>(base, definition),

  /** Saves a new revision; a stale `expectedRevision` answers 409 (someone else saved meanwhile). */
  update: (id: number, definition: ChartDefinition, expectedRevision: number) =>
    putJson<Chart>(`${base}/${id}`, { definition, expectedRevision }),

  duplicate: (id: number, input: DuplicateInput = {}) => postJson<Chart>(`${base}/${id}/duplicate`, input),

  remove: (id: number) => deleteJson(`${base}/${id}`),

  /** Newest first. */
  revisions: (id: number, signal?: AbortSignal) => getJson<ChartRevisionSummary[]>(`${base}/${id}/revisions`, {}, signal),

  revision: (id: number, revision: number, signal?: AbortSignal) =>
    getJson<ChartRevision>(`${base}/${id}/revisions/${revision}`, {}, signal),

  /** Runs the current revision, or an older one. */
  data: (id: number, revision: number | null = null, signal?: AbortSignal) =>
    getJson<ChartResult>(`${base}/${id}/data`, { revision }, signal),
}
