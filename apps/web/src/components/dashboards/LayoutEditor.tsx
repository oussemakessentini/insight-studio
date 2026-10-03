import {
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
  type PointerEvent,
  type ReactNode,
} from 'react'
import type { LayoutGrid, LayoutItem } from '../../api/dashboards'
import { useElementWidth } from '../../hooks/useElementWidth'
import {
  ACTION_LABELS,
  actionForKey,
  applyAction,
  byPosition,
  cellStyle,
  clampToGrid,
  describePlace,
  describeSize,
  gridBottom,
  MAX_HEIGHT,
  MAX_ROWS,
  MIN_HEIGHT,
  placeItem,
  ROW_HEIGHT,
  type GridName,
  type LayoutAction,
} from '../../lib/dashboardLayout'
import { ArrowDownIcon, ArrowLeftIcon, ArrowRightIcon, ArrowUpIcon, GripIcon, ResizeIcon } from '../Icons'

/** What the editor shows about a widget. */
export interface EditorWidget {
  id: string
  title: string
  /** "Line chart"; null for a deleted chart. */
  kind: string | null
  missing: boolean
}

interface LayoutEditorProps {
  grid: LayoutGrid
  gridName: GridName
  widgets: Map<string, EditorWidget>
  onItemsChange: (items: LayoutItem[]) => void
  /** Said by the live region: the new position or size, or why a step isn't possible. */
  onAnnounce: (message: string) => void
  onRemove: (id: string) => void
}

/** Below this column width the grid can't hold its cards: they are listed, with a map of the grid above. */
const MIN_COLUMN_WIDTH = 48
const GAPS: Record<GridName, number> = { desktop: 16, mobile: 12 }
const MOVES: LayoutAction[] = ['left', 'up', 'down', 'right']
const SIZES: LayoutAction[] = ['narrower', 'wider', 'shorter', 'taller']
const MOVE_ICONS: Record<string, ReactNode> = {
  left: <ArrowLeftIcon />,
  up: <ArrowUpIcon />,
  down: <ArrowDownIcon />,
  right: <ArrowRightIcon />,
}

interface DragSession {
  id: string
  mode: 'move' | 'resize'
  pointerId: number
  /** Page coordinates (scrolling while dragging counts). */
  startX: number
  startY: number
  columnStep: number
  rowStep: number
  origin: LayoutItem[]
  /** The last previewed rectangle, to skip identical updates. */
  last: string
}

/**
 * One grid of a dashboard being arranged. Cards move and resize by dragging their handle and
 * corner, or with the buttons on each card and the keyboard on the handle (arrows move, Shift +
 * arrows resize). A card takes the spot it is given; cards it would cover move down.
 */
export function LayoutEditor({ grid, gridName, widgets, onItemsChange, onAnnounce, onRemove }: LayoutEditorProps) {
  const instructionsId = useId()
  const [measure, width] = useElementWidth<HTMLDivElement>()
  const rootRef = useRef<HTMLDivElement | null>(null)
  const gridRef = useRef<HTMLDivElement>(null)
  const session = useRef<DragSession | null>(null)
  const pendingFocus = useRef<{ id: string; control: string } | null>(null)
  const [preview, setPreview] = useState<{ id: string; items: LayoutItem[] } | null>(null)

  const { columns } = grid
  const gap = GAPS[gridName]
  const columnWidth = width === undefined ? undefined : (width - gap * (columns - 1)) / columns
  const listMode = columnWidth !== undefined && columnWidth < MIN_COLUMN_WIDTH
  const items = preview?.items ?? grid.items
  const ordered = byPosition(grid.items).map((saved) => items.find((i) => i.id === saved.id) ?? saved)
  // Numbers follow reading order of the saved positions, so they don't jump around mid-drag.
  const numbers = new Map(byPosition(grid.items).map((item, i) => [item.id, i + 1]))
  const titleOf = (id: string) => widgets.get(id)?.title ?? 'This chart'

  // A card re-renders in reading order after a keyboard move; keep focus on the control that moved it.
  useLayoutEffect(() => {
    const request = pendingFocus.current
    if (!request || !rootRef.current) return
    pendingFocus.current = null
    const target = rootRef.current.querySelector<HTMLElement>(`[data-widget="${request.id}"][data-control="${request.control}"]`)
    if (target && document.activeElement !== target) target.focus()
  })

  const describeResult = (id: string, next: LayoutItem[], pushed: string[], resized: boolean) => {
    const item = next.find((i) => i.id === id)
    if (!item) return ''
    const where = resized ? `is now ${describeSize(item)}, ${describePlace(item)}` : `moved to ${describePlace(item)}`
    const others = pushed.length > 0 ? ` ${pushed.length} other ${pushed.length === 1 ? 'chart' : 'charts'} moved down.` : ''
    return `${titleOf(id)} ${where}.${others}`
  }

  const act = (id: string, action: LayoutAction, control: string) => {
    const result = applyAction(grid.items, columns, id, action)
    if (!result.ok) {
      onAnnounce(`${titleOf(id)}: ${result.reason}`)
      return
    }
    pendingFocus.current = { id, control }
    onItemsChange(result.items)
    onAnnounce(describeResult(id, result.items, result.pushed, SIZES.includes(action)))
  }

  // ---- Pointer dragging ------------------------------------------------------------------------

  const startDrag = (event: PointerEvent<HTMLElement>, id: string, mode: 'move' | 'resize') => {
    if (listMode || event.button !== 0 || !gridRef.current) return
    const rect = gridRef.current.getBoundingClientRect()
    session.current = {
      id,
      mode,
      pointerId: event.pointerId,
      startX: event.clientX + window.scrollX,
      startY: event.clientY + window.scrollY,
      columnStep: (rect.width + gap) / columns,
      rowStep: ROW_HEIGHT + gap,
      origin: grid.items,
      last: '',
    }
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  const moveDrag = (event: PointerEvent<HTMLElement>) => {
    const s = session.current
    if (!s || event.pointerId !== s.pointerId) return
    const dx = Math.round((event.clientX + window.scrollX - s.startX) / s.columnStep)
    const dy = Math.round((event.clientY + window.scrollY - s.startY) / s.rowStep)
    const origin = s.origin.find((i) => i.id === s.id)
    if (!origin) return
    const next =
      s.mode === 'move'
        ? clampToGrid({ ...origin, x: origin.x + dx, y: origin.y + dy }, columns)
        : {
            ...origin,
            w: Math.min(Math.max(origin.w + dx, 1), columns - origin.x),
            h: Math.min(Math.max(origin.h + dy, MIN_HEIGHT), MAX_HEIGHT, MAX_ROWS - origin.y),
          }
    const key = `${next.x},${next.y},${next.w},${next.h}`
    if (key === s.last) return
    s.last = key
    const placed = placeItem(s.origin, columns, s.id, next)
    // A spot that would push others past the last row keeps the previous preview.
    if (placed.ok) setPreview({ id: s.id, items: placed.items })
    // Keep the dragged card in view near the window's edges.
    if (event.clientY > window.innerHeight - 48) window.scrollBy(0, 16)
    else if (event.clientY < 48) window.scrollBy(0, -16)
  }

  const endDrag = (event: PointerEvent<HTMLElement>, cancelled = false) => {
    const s = session.current
    if (!s || event.pointerId !== s.pointerId) return
    session.current = null
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
    const result = preview
    setPreview(null)
    if (cancelled || !result) return
    const before = s.origin.find((i) => i.id === s.id)
    const after = result.items.find((i) => i.id === s.id)
    if (!before || !after || (before.x === after.x && before.y === after.y && before.w === after.w && before.h === after.h)) return
    const pushed = result.items.filter((i) => i.id !== s.id && s.origin.find((o) => o.id === i.id)?.y !== i.y).map((i) => i.id)
    onItemsChange(result.items)
    onAnnounce(describeResult(s.id, result.items, pushed, s.mode === 'resize'))
  }

  const onHandleKeyDown = (event: KeyboardEvent<HTMLElement>, id: string) => {
    if (event.key === 'Escape' && session.current) {
      session.current = null
      setPreview(null)
      onAnnounce('Move cancelled.')
      return
    }
    const action = actionForKey(event.key, event.shiftKey)
    if (!action || event.altKey || event.ctrlKey || event.metaKey) return
    event.preventDefault()
    act(id, action, 'handle')
  }

  const dragHandlers = (id: string, mode: 'move' | 'resize') => ({
    onPointerDown: (e: PointerEvent<HTMLElement>) => startDrag(e, id, mode),
    onPointerMove: moveDrag,
    onPointerUp: (e: PointerEvent<HTMLElement>) => endDrag(e),
    onPointerCancel: (e: PointerEvent<HTMLElement>) => endDrag(e, true),
    onLostPointerCapture: (e: PointerEvent<HTMLElement>) => endDrag(e, true),
  })

  const card = (item: LayoutItem) => {
    const widget = widgets.get(item.id)
    return (
      <EditorCard
        item={item}
        number={numbers.get(item.id) ?? 0}
        widget={widget}
        listMode={listMode}
        instructionsId={instructionsId}
        possible={(action) => applyAction(grid.items, columns, item.id, action).ok}
        onAction={(action) => act(item.id, action, action)}
        onRemove={() => onRemove(item.id)}
        handleProps={{ ...dragHandlers(item.id, 'move'), onKeyDown: (e: KeyboardEvent<HTMLElement>) => onHandleKeyDown(e, item.id) }}
        resizeProps={dragHandlers(item.id, 'resize')}
      />
    )
  }

  const rows = Math.max(gridBottom(items) + 2, 6)
  const style = { '--grid-columns': columns, '--grid-rows': rows } as CSSProperties

  return (
    <div
      className={`dashboard-editor dashboard-editor-${gridName}`}
      ref={(element) => {
        rootRef.current = element
        measure(element)
      }}
    >
      <p className="form-hint" id={instructionsId}>
        {listMode
          ? 'This screen is too narrow to show the grid itself: the map shows where each numbered chart sits. Use the buttons on each chart, or focus its handle and press the arrow keys to move it and Shift + arrow keys to resize it.'
          : 'Drag a chart by its handle to move it and by its corner to resize it. With the keyboard, focus the handle and press the arrow keys to move it, Shift + arrow keys to resize it. Charts in the way move down.'}
      </p>
      {listMode ? (
        <>
          <div className="dashboard-minimap" style={{ ...style, '--grid-rows': Math.max(gridBottom(items), 1) } as CSSProperties} aria-hidden="true">
            {items.map((item) => (
              <span key={item.id} className={widgets.get(item.id)?.missing ? 'is-missing' : undefined} style={cellStyle(item)}>
                {numbers.get(item.id)}
              </span>
            ))}
          </div>
          <ol className="dashboard-editor-list">
            {ordered.map((item) => (
              <li key={item.id}>{card(item)}</li>
            ))}
          </ol>
        </>
      ) : (
        <div ref={gridRef} className={`dashboard-grid dashboard-grid-${gridName} dashboard-grid-editing`} style={style}>
          {/* Column guides behind the cards. */}
          {Array.from({ length: columns }, (_, i) => (
            <span key={`guide-${i}`} className="dashboard-guide" style={{ gridColumn: i + 1, gridRow: `1 / span ${rows}` }} aria-hidden="true" />
          ))}
          {/* Rendered in the saved reading order: moving a node mid-drag would drop its pointer capture. */}
          {ordered.map((item) => (
            <div key={item.id} className={`dashboard-cell ${preview?.id === item.id ? 'is-dragging' : ''}`} style={cellStyle(item)}>
              {card(item)}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

type Handlers = Record<string, (event: never) => void>

function EditorCard({
  item,
  number,
  widget,
  listMode,
  instructionsId,
  possible,
  onAction,
  onRemove,
  handleProps,
  resizeProps,
}: {
  item: LayoutItem
  number: number
  widget: EditorWidget | undefined
  listMode: boolean
  instructionsId: string
  possible: (action: LayoutAction) => boolean
  onAction: (action: LayoutAction) => void
  onRemove: () => void
  handleProps: Handlers
  resizeProps: Handlers
}) {
  const titleId = useId()
  const missing = widget?.missing ?? false
  const title = widget?.title ?? 'Chart'
  const button = (action: LayoutAction, content: ReactNode, className: string) => {
    const allowed = possible(action)
    return (
      <button
        key={action}
        type="button"
        className={`button button-secondary button-small ${className}`}
        data-widget={item.id}
        data-control={action}
        // Not `disabled`: focus stays on the button at an edge, and pressing it says why.
        aria-disabled={!allowed || undefined}
        aria-label={`${ACTION_LABELS[action]}: ${title}`}
        title={ACTION_LABELS[action]}
        onClick={() => onAction(action)}
      >
        {content}
      </button>
    )
  }

  return (
    <article className={`dashboard-widget dashboard-editor-card ${missing ? 'dashboard-widget-missing' : ''}`} aria-labelledby={titleId}>
      <header className="dashboard-widget-header dashboard-editor-header">
        <button
          type="button"
          className="dashboard-handle"
          data-widget={item.id}
          data-control="handle"
          aria-label={`Move or resize ${title}, ${describePlace(item)}`}
          aria-describedby={instructionsId}
          {...handleProps}
        >
          <GripIcon />
        </button>
        <span className="dashboard-editor-number" aria-hidden="true">
          {number}
        </span>
        <h2 className="dashboard-widget-title" id={titleId} title={title}>
          {title}
        </h2>
        <button
          type="button"
          className="button button-secondary button-small"
          data-widget={item.id}
          data-control="remove"
          onClick={onRemove}
          aria-label={`Remove ${title}`}
        >
          Remove
        </button>
      </header>
      <div className="dashboard-widget-body dashboard-editor-body">
        <p className="dashboard-editor-place">
          {missing ? 'Remove it before saving: a dashboard can only be saved without deleted charts. ' : widget?.kind ? `${widget.kind} · ` : ''}
          <span className="nowrap">
            {item.w} × {item.h}
          </span>{' '}
          · {describePlace(item)}
        </p>
        <div className="dashboard-editor-controls">
          <div className="dashboard-control-group" role="group" aria-label={`Move ${title}`}>
            {MOVES.map((action) => button(action, MOVE_ICONS[action], 'dashboard-icon-button'))}
          </div>
          <div className="dashboard-control-group" role="group" aria-label={`Resize ${title}`}>
            {SIZES.map((action) => button(action, ACTION_LABELS[action], ''))}
          </div>
        </div>
      </div>
      {!listMode && (
        <span className="dashboard-resize" aria-hidden="true" title="Drag to resize" {...resizeProps}>
          <ResizeIcon width={16} height={16} />
        </span>
      )}
    </article>
  )
}
