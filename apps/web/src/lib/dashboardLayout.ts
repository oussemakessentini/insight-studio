// Dashboard layouts (docs/dashboards-contract.md §1, §2): pure functions that keep a layout valid
// while it is edited. The server validates every save again; these rules mirror its checks so the
// editor never offers a layout it would refuse.
import type { DashboardLayout, DashboardWidget, LayoutGrid, LayoutItem } from '../api/dashboards'

export type GridName = 'desktop' | 'mobile'

export const GRID_COLUMNS: Record<GridName, number> = { desktop: 12, mobile: 4 }
export const GRID_LABELS: Record<GridName, string> = { desktop: 'Desktop', mobile: 'Mobile' }
export const MIN_HEIGHT = 2
export const MAX_HEIGHT = 12
/** `y + h` may not go past this row. */
export const MAX_ROWS = 200
export const MAX_WIDGETS = 24
/** One grid row on screen (the API only sees units). */
export const ROW_HEIGHT = 80
/** Viewports at least this wide show the desktop layout; narrower ones the mobile layout. */
export const DESKTOP_MIN_WIDTH = 1024

/** Where a new widget goes: half the desktop width, the full mobile width, four rows tall. */
export const DEFAULT_SIZE: Record<GridName, { w: number; h: number }> = {
  desktop: { w: 6, h: 4 },
  mobile: { w: 4, h: 4 },
}

const WIDGET_ID = /^[A-Za-z0-9_-]{1,40}$/

export function overlaps(a: Omit<LayoutItem, 'id'>, b: Omit<LayoutItem, 'id'>): boolean {
  return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
}

/** Items in reading order: top to bottom, then left to right. */
export function byPosition(items: LayoutItem[]): LayoutItem[] {
  return [...items].sort((a, b) => a.y - b.y || a.x - b.x)
}

/** The first row below every item. */
export function gridBottom(items: LayoutItem[]): number {
  return items.reduce((bottom, item) => Math.max(bottom, item.y + item.h), 0)
}

/** "columns 1–6, rows 1–4": a position as people count it (from 1). */
export function describePlace(item: Omit<LayoutItem, 'id'>): string {
  const columns = item.w === 1 ? `column ${item.x + 1}` : `columns ${item.x + 1} to ${item.x + item.w}`
  return `${columns}, rows ${item.y + 1} to ${item.y + item.h}`
}

/** "6 columns wide, 4 rows tall" */
export function describeSize(item: Omit<LayoutItem, 'id'>): string {
  return `${item.w} ${item.w === 1 ? 'column' : 'columns'} wide, ${item.h} rows tall`
}

/** The highest, then leftmost, free spot of `w` × `h`; null when the grid is full. */
export function findFreeSpot(items: LayoutItem[], columns: number, w: number, h: number): { x: number; y: number } | null {
  const width = Math.min(w, columns)
  for (let y = 0; y + h <= MAX_ROWS; y++) {
    for (let x = 0; x + width <= columns; x++) {
      const candidate = { x, y, w: width, h }
      if (!items.some((item) => overlaps(candidate, item))) return candidate
    }
  }
  return null
}

/** A widget id that isn't in the layout yet (`w-` and six hex digits, within the contract's pattern). */
export function newWidgetId(layout: DashboardLayout): string {
  const taken = new Set(layout.widgets.map((w) => w.id))
  for (;;) {
    const id = `w-${Math.floor(Math.random() * 0x1000000)
      .toString(16)
      .padStart(6, '0')}`
    if (!taken.has(id)) return id
  }
}

/** Adds a widget at the first free spot of both grids; null when either grid has no room. */
export function addWidget(layout: DashboardLayout, chartId: number): { layout: DashboardLayout; id: string } | null {
  if (layout.widgets.length >= MAX_WIDGETS) return null
  const id = newWidgetId(layout)
  const place = (grid: LayoutGrid, name: GridName): LayoutGrid | null => {
    const { w, h } = DEFAULT_SIZE[name]
    const spot = findFreeSpot(grid.items, grid.columns, w, h)
    return spot ? { ...grid, items: [...grid.items, { id, ...spot, w: Math.min(w, grid.columns), h }] } : null
  }
  const desktop = place(layout.desktop, 'desktop')
  const mobile = place(layout.mobile, 'mobile')
  if (!desktop || !mobile) return null
  return { id, layout: { ...layout, widgets: [...layout.widgets, { id, chartId }], desktop, mobile } }
}

export function removeWidgets(layout: DashboardLayout, ids: Set<string>): DashboardLayout {
  return {
    ...layout,
    widgets: layout.widgets.filter((w) => !ids.has(w.id)),
    desktop: { ...layout.desktop, items: layout.desktop.items.filter((i) => !ids.has(i.id)) },
    mobile: { ...layout.mobile, items: layout.mobile.items.filter((i) => !ids.has(i.id)) },
  }
}

/** The layout without the widgets of deleted charts: the server refuses to save those (contract §3). */
export function withoutMissingWidgets(layout: DashboardLayout, widgets: DashboardWidget[]): DashboardLayout {
  const missing = new Set(widgets.filter((w) => w.missing || w.chart === null).map((w) => w.id))
  return missing.size === 0 ? layout : removeWidgets(layout, missing)
}

export type Placement = { ok: true; items: LayoutItem[]; pushed: string[] } | { ok: false; reason: string }

/**
 * Puts one item at `next`. The item takes that spot; any item it would cover moves down, just
 * below whatever covers it (and so on, in cascade), so a move or resize never leaves an overlap.
 * Refused when `next` is out of bounds or a pushed item would fall past the last row.
 */
export function placeItem(items: LayoutItem[], columns: number, id: string, next: Omit<LayoutItem, 'id'>): Placement {
  const outOfBounds = boundsProblem(next, columns)
  if (outOfBounds) return { ok: false, reason: outOfBounds }
  const placed: LayoutItem[] = items.map((item) => (item.id === id ? { id, ...next } : { ...item }))
  const fixed = placed.find((item) => item.id === id)
  if (!fixed) return { ok: false, reason: 'This widget is not on the grid.' }

  const pushed = new Set<string>()
  const queue: LayoutItem[] = [fixed]
  while (queue.length > 0) {
    const above = queue.shift()!
    // Top to bottom, so nearer items are pushed first and the order of the column is kept.
    for (const item of byPosition(placed)) {
      if (item === above || item === fixed || !overlaps(item, above)) continue
      item.y = above.y + above.h
      // An item pushed below something else must not end up under the item being placed.
      while (overlaps(item, fixed)) item.y = fixed.y + fixed.h
      if (item.y + item.h > MAX_ROWS) return { ok: false, reason: 'There is no room to move the other widgets down.' }
      pushed.add(item.id)
      queue.push(item)
    }
  }
  return { ok: true, items: placed, pushed: [...pushed] }
}

function boundsProblem(item: Omit<LayoutItem, 'id'>, columns: number): string | null {
  if (item.x < 0) return 'It is already at the left edge.'
  if (item.y < 0) return 'It is already at the top.'
  if (item.w < 1) return 'It is already as narrow as it can be (1 column).'
  if (item.w > columns) return `It is already as wide as it can be (${columns} columns).`
  if (item.x + item.w > columns) return 'It is already at the right edge.'
  if (item.h < MIN_HEIGHT) return `It is already as short as it can be (${MIN_HEIGHT} rows).`
  if (item.h > MAX_HEIGHT) return `It is already as tall as it can be (${MAX_HEIGHT} rows).`
  if (item.y + item.h > MAX_ROWS) return 'It is already at the bottom of the grid.'
  return null
}

export type LayoutAction = 'left' | 'right' | 'up' | 'down' | 'wider' | 'narrower' | 'taller' | 'shorter'

export const ACTION_LABELS: Record<LayoutAction, string> = {
  left: 'Move left',
  right: 'Move right',
  up: 'Move up',
  down: 'Move down',
  wider: 'Wider',
  narrower: 'Narrower',
  taller: 'Taller',
  shorter: 'Shorter',
}

/** Keyboard shortcuts on a widget's handle: arrows move, Shift + arrows resize. */
export const ACTION_KEYS: Record<LayoutAction, string> = {
  left: 'ArrowLeft',
  right: 'ArrowRight',
  up: 'ArrowUp',
  down: 'ArrowDown',
  wider: 'Shift+ArrowRight',
  narrower: 'Shift+ArrowLeft',
  taller: 'Shift+ArrowDown',
  shorter: 'Shift+ArrowUp',
}

export function actionForKey(key: string, shift: boolean): LayoutAction | null {
  switch (key) {
    case 'ArrowLeft':
      return shift ? 'narrower' : 'left'
    case 'ArrowRight':
      return shift ? 'wider' : 'right'
    case 'ArrowUp':
      return shift ? 'shorter' : 'up'
    case 'ArrowDown':
      return shift ? 'taller' : 'down'
    default:
      return null
  }
}

/** One step of a move or resize. */
export function applyAction(items: LayoutItem[], columns: number, id: string, action: LayoutAction): Placement {
  const item = items.find((i) => i.id === id)
  if (!item) return { ok: false, reason: 'This widget is not on the grid.' }
  const { x, y, w, h } = item
  const next = {
    left: { x: x - 1, y, w, h },
    right: { x: x + 1, y, w, h },
    up: { x, y: y - 1, w, h },
    down: { x, y: y + 1, w, h },
    wider: { x, y, w: w + 1, h },
    narrower: { x, y, w: w - 1, h },
    taller: { x, y, w, h: h + 1 },
    shorter: { x, y, w, h: h - 1 },
  }[action]
  return placeItem(items, columns, id, next)
}

/** A rectangle within the grid's bounds (for dragging past an edge). */
export function clampToGrid(item: Omit<LayoutItem, 'id'>, columns: number): Omit<LayoutItem, 'id'> {
  const w = Math.min(Math.max(item.w, 1), columns)
  const h = Math.min(Math.max(item.h, MIN_HEIGHT), MAX_HEIGHT)
  return {
    w,
    h,
    x: Math.min(Math.max(item.x, 0), columns - w),
    y: Math.min(Math.max(item.y, 0), MAX_ROWS - h),
  }
}

/** Everything the server checks about a layout (contract §2), as messages; empty when valid. */
export function layoutProblems(layout: DashboardLayout): string[] {
  const problems: string[] = []
  if (layout.widgets.length > MAX_WIDGETS) problems.push(`A dashboard can show at most ${MAX_WIDGETS} charts.`)
  const ids = new Set<string>()
  for (const widget of layout.widgets) {
    if (!WIDGET_ID.test(widget.id)) problems.push(`Widget id “${widget.id}” is not valid.`)
    if (ids.has(widget.id)) problems.push(`Widget id “${widget.id}” is used twice.`)
    ids.add(widget.id)
  }
  for (const name of ['desktop', 'mobile'] as const) {
    const grid = layout[name]
    const label = GRID_LABELS[name]
    if (grid.columns !== GRID_COLUMNS[name]) problems.push(`The ${label.toLowerCase()} grid must have ${GRID_COLUMNS[name]} columns.`)
    const seen = new Set<string>()
    for (const item of grid.items) {
      if (!ids.has(item.id)) problems.push(`${label}: an item refers to an unknown widget.`)
      if (seen.has(item.id)) problems.push(`${label}: a widget is placed twice.`)
      seen.add(item.id)
      const bounds = boundsProblem(item, grid.columns)
      if (bounds || ![item.x, item.y, item.w, item.h].every(Number.isInteger)) problems.push(`${label}: a widget is outside the grid.`)
    }
    for (const id of ids) if (!seen.has(id)) problems.push(`${label}: a widget has no place.`)
    grid.items.forEach((a, i) => {
      if (grid.items.slice(i + 1).some((b) => overlaps(a, b))) problems.push(`${label}: two widgets overlap.`)
    })
  }
  return problems
}

/** Whether two layouts are the same (to tell whether there is anything to save). */
export function sameLayout(a: DashboardLayout, b: DashboardLayout): boolean {
  return JSON.stringify(normalized(a)) === JSON.stringify(normalized(b))
}

function normalized(layout: DashboardLayout) {
  const items = (grid: LayoutGrid) => [...grid.items].sort((a, b) => a.id.localeCompare(b.id)).map(({ id, x, y, w, h }) => [id, x, y, w, h])
  return {
    widgets: layout.widgets.map(({ id, chartId }) => [id, chartId]),
    desktop: items(layout.desktop),
    mobile: items(layout.mobile),
  }
}
