// Custom dashboards API (docs/dashboards-contract.md §1, §4). A dashboard is a name and a layout of
// widgets, each showing a saved chart by id, placed on a 12-column desktop grid and a 4-column
// mobile grid. Every save is a new revision; a stale `expectedRevision` answers 409.
import { ApiError, deleteJson, getJson, postJson, putJson } from './client'
import type { ChartVisualization } from './charts'

/** One widget's place on a grid, in grid units (columns, 80 px rows). */
export interface LayoutItem {
  id: string
  x: number
  y: number
  w: number
  h: number
}

export interface LayoutGrid {
  columns: number
  items: LayoutItem[]
}

/** A widget shows a chart by id, always its current revision. */
export interface LayoutWidget {
  id: string
  chartId: number
}

export interface DashboardLayout {
  schemaVersion: 1
  widgets: LayoutWidget[]
  desktop: LayoutGrid
  mobile: LayoutGrid
}

/** The live chart behind a widget, or `null` once the chart was deleted (`missing: true`). */
export interface DashboardWidgetChart {
  id: number
  title: string
  visualization: ChartVisualization
  revision: number
}

export interface DashboardWidget {
  id: string
  chartId: number
  missing: boolean
  chart: DashboardWidgetChart | null
}

export interface Dashboard {
  id: number
  name: string
  revision: number
  layout: DashboardLayout
  /** In layout order. */
  widgets: DashboardWidget[]
  createdBy: string | null
  updatedBy: string | null
  createdAt: string
  updatedAt: string
}

export interface DashboardSummary {
  id: number
  name: string
  revision: number
  widgetCount: number
  missingCount: number
  updatedBy: string | null
  updatedAt: string
}

export interface DashboardRevisionSummary {
  revision: number
  name: string
  widgetCount: number
  createdBy: string | null
  createdAt: string
}

/** A dashboard that uses a chart (`GET /api/charts/{id}/dashboards`). */
export interface DashboardRef {
  id: number
  name: string
}

/** What a stale save's 409 says about the version that won. */
export interface DashboardConflict {
  currentRevision: number
  updatedBy: string | null
  updatedAt: string | null
}

/**
 * The 409 of a stale `expectedRevision` (it carries `currentRevision`), or null for any other
 * error, including the 409 of a taken name.
 */
export function dashboardConflict(error: unknown): DashboardConflict | null {
  if (!(error instanceof ApiError) || error.status !== 409 || !error.problem) return null
  const { currentRevision, updatedBy, updatedAt } = error.problem
  if (typeof currentRevision !== 'number') return null
  return {
    currentRevision,
    updatedBy: typeof updatedBy === 'string' ? updatedBy : null,
    updatedAt: typeof updatedAt === 'string' ? updatedAt : null,
  }
}

export const EMPTY_LAYOUT: DashboardLayout = {
  schemaVersion: 1,
  widgets: [],
  desktop: { columns: 12, items: [] },
  mobile: { columns: 4, items: [] },
}

const base = '/api/dashboards'

export const dashboardsApi = {
  /** By name. */
  list: (signal?: AbortSignal) => getJson<DashboardSummary[]>(base, {}, signal),

  /** The current revision, or an older one (read-only). */
  get: (id: number, revision: number | null = null, signal?: AbortSignal) =>
    getJson<Dashboard>(`${base}/${id}`, { revision }, signal),

  /** An empty layout when none is given. */
  create: (name: string, layout?: DashboardLayout) => postJson<Dashboard>(base, layout ? { name, layout } : { name }),

  /** Rename and layout changes are both saves; a stale `expectedRevision` answers 409. */
  update: (id: number, name: string, layout: DashboardLayout, expectedRevision: number) =>
    putJson<Dashboard>(`${base}/${id}`, { name, layout, expectedRevision }),

  /** Copies the current layout (without deleted charts); the server names it "Copy of …" without a name. */
  duplicate: (id: number, name?: string) => postJson<Dashboard>(`${base}/${id}/duplicate`, name ? { name } : {}),

  remove: (id: number) => deleteJson(`${base}/${id}`),

  /** Newest first. */
  revisions: (id: number, signal?: AbortSignal) => getJson<DashboardRevisionSummary[]>(`${base}/${id}/revisions`, {}, signal),
}
