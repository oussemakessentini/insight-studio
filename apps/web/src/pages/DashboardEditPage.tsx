import { useId, useRef, useState, type KeyboardEvent } from 'react'
import { chartsApi, type ChartSummary } from '../api/charts'
import { ApiError } from '../api/client'
import { dashboardConflict, dashboardsApi, type Dashboard, type DashboardConflict, type DashboardLayout, type LayoutItem } from '../api/dashboards'
import { ChartPicker } from '../components/dashboards/ChartPicker'
import { LayoutEditor, type EditorWidget } from '../components/dashboards/LayoutEditor'
import { Dialog } from '../components/Dialog'
import { FormError, TextField } from '../components/Form'
import { PlusIcon } from '../components/Icons'
import { Link } from '../components/Link'
import { PageHeader } from '../components/PageHeader'
import { ErrorState, Panel, Skeleton } from '../components/Panel'
import { useApi, type ApiState } from '../hooks/useApi'
import { VISUALIZATION_LABELS } from '../lib/charts'
import {
  addWidget,
  describePlace,
  GRID_COLUMNS,
  GRID_LABELS,
  layoutProblems,
  MAX_WIDGETS,
  removeWidgets,
  sameLayout,
  type GridName,
} from '../lib/dashboardLayout'
import { DASHBOARD_NAME_MAX, dashboardNameError } from '../lib/dashboards'
import { formatDateTimeLong } from '../lib/format'
import { navigate, updateQuery } from '../lib/router'
import { errorMessage } from '../lib/validation'
import '../styles/accounts.css'
import '../styles/reports.css'
import '../styles/charts.css'
import '../styles/dashboards.css'
import type { PageProps } from './types'

const GRIDS: GridName[] = ['desktop', 'mobile']

/** `?layout=mobile` opens the mobile tab, so a refresh comes back to it. */
function gridFromUrl(): GridName {
  return new URLSearchParams(window.location.search).get('layout') === 'mobile' ? 'mobile' : 'desktop'
}

/** Loads the current revision, then arranges it. "Reload latest" loads again and starts over. */
export function DashboardEditPage({ dashboardId, context, href }: PageProps & { dashboardId: number }) {
  const [loadVersion, setLoadVersion] = useState(0)
  const dashboard = useApi(`dashboard-edit|${dashboardId}|${loadVersion}`, (signal) => dashboardsApi.get(dashboardId, null, signal))
  const charts = useApi(`charts|picker`, (signal) => chartsApi.list(signal))
  const listHref = href('/dashboards')

  if (dashboard.error instanceof ApiError && dashboard.error.status === 404) {
    return (
      <div className="panel page-error">
        <h1 className="page-title">Dashboard not found</h1>
        <ErrorState message="It may have been deleted, or it belongs to another business." />
        <p className="page-error-action">
          <Link className="button button-secondary" href={listHref}>
            See all dashboards
          </Link>
        </p>
      </div>
    )
  }
  if (dashboard.error) {
    return (
      <div className="panel page-error">
        <h1 className="page-title">This dashboard couldn’t be loaded</h1>
        <ErrorState message={dashboard.error.message} onRetry={dashboard.retry} />
      </div>
    )
  }
  if (!dashboard.data || dashboard.loading) {
    return (
      <div aria-busy="true" aria-label="Loading">
        <div className="page-header">
          <div>
            <Skeleton height={14} width={160} />
            <Skeleton height={30} width={280} />
          </div>
        </div>
        <Panel title="Layout">
          <Skeleton height={360} />
        </Panel>
      </div>
    )
  }
  return (
    <DashboardEditor
      // A reload starts over from what the server has now.
      key={`${dashboard.data.id}|${dashboard.data.revision}|${loadVersion}`}
      initial={dashboard.data}
      charts={charts}
      context={context}
      href={href}
      onReload={() => setLoadVersion((v) => v + 1)}
    />
  )
}

interface EditorProps {
  initial: Dashboard
  charts: ApiState<ChartSummary[]>
  context: PageProps['context']
  href: PageProps['href']
  onReload: () => void
}

function DashboardEditor({ initial, charts, context, href, onReload }: EditorProps) {
  const { business } = context
  const tabsId = useId()
  const [name, setName] = useState(initial.name)
  const [layout, setLayout] = useState<DashboardLayout>(initial.layout)
  // The revision this edit started from; "Keep editing" after a conflict moves it to the winner's.
  const [baseRevision, setBaseRevision] = useState(initial.revision)
  const [gridName, setGridName] = useState<GridName>(gridFromUrl)
  // Live charts behind widgets: the dashboard's own, then those added from the picker.
  const [chartInfo, setChartInfo] = useState(() => new Map(initial.widgets.map((w) => [w.id, w.chart])))
  const [announcement, setAnnouncement] = useState('')
  const [picking, setPicking] = useState(false)
  const [submitted, setSubmitted] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<{ message: string; details: string[] } | null>(null)
  const [nameTaken, setNameTaken] = useState<string | null>(null)
  const [conflict, setConflict] = useState<DashboardConflict | null>(null)
  const alertRef = useRef<HTMLDivElement>(null)

  const viewHref = href(`/dashboards/${initial.id}`)
  const nameError = nameTaken ?? dashboardNameError(name)
  const missingIds = new Set(layout.widgets.filter((w) => chartInfo.get(w.id) == null).map((w) => w.id))
  const dirty = name.trim() !== initial.name || !sameLayout(layout, initial.layout)
  const placed = new Set(layout.widgets.map((w) => w.chartId))
  const full =
    layout.widgets.length >= MAX_WIDGETS ? `A dashboard can show at most ${MAX_WIDGETS} charts. Remove one to add another.` : null

  const widgets = new Map<string, EditorWidget>(
    layout.widgets.map((w) => {
      const chart = chartInfo.get(w.id)
      return [
        w.id,
        chart
          ? { id: w.id, title: chart.title, kind: VISUALIZATION_LABELS[chart.visualization] ?? chart.visualization, missing: false }
          : { id: w.id, title: `Deleted chart (${w.chartId})`, kind: null, missing: true },
      ]
    }),
  )

  // A new message each time, even when the text repeats, so screen readers say it again.
  const announce = (message: string) => setAnnouncement((previous) => (previous === message ? `${message}\u00a0` : message))

  const setItems = (items: LayoutItem[]) => setLayout((current) => ({ ...current, [gridName]: { ...current[gridName], items } }))

  const remove = (ids: Set<string>, message: string) => {
    setLayout((current) => removeWidgets(current, ids))
    announce(message)
  }

  const switchGrid = (next: GridName) => {
    setGridName(next)
    updateQuery({ layout: next === 'mobile' ? 'mobile' : null })
  }

  const onTabKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
    event.preventDefault()
    const next = gridName === 'desktop' ? 'mobile' : 'desktop'
    switchGrid(next)
    document.getElementById(`${tabsId}-${next}`)?.focus()
  }

  const add = (chart: ChartSummary) => {
    const result = addWidget(layout, chart.id)
    if (!result) {
      announce(`There is no room left for ${chart.title}.`)
      return
    }
    setLayout(result.layout)
    setChartInfo((current) => new Map(current).set(result.id, { id: chart.id, title: chart.title, visualization: chart.visualization, revision: chart.revision }))
    setPicking(false)
    const item = result.layout[gridName].items.find((i) => i.id === result.id)
    announce(`Added ${chart.title} at ${item ? describePlace(item) : 'the first free spot'} of the ${GRID_LABELS[gridName].toLowerCase()} layout.`)
  }

  const showAlert = () => requestAnimationFrame(() => alertRef.current?.scrollIntoView({ block: 'center', behavior: 'smooth' }))

  const save = async () => {
    setSubmitted(true)
    setError(null)
    const problems = layoutProblems(layout)
    if (nameError || missingIds.size > 0 || problems.length > 0) {
      if (problems.length > 0) setError({ message: 'The layout can’t be saved as it is:', details: problems })
      showAlert()
      return
    }
    setSaving(true)
    try {
      await dashboardsApi.update(initial.id, name.trim(), layout, baseRevision)
      navigate(viewHref)
    } catch (err) {
      setSaving(false)
      const stale = dashboardConflict(err)
      if (stale) {
        setConflict(stale)
      } else if (err instanceof ApiError && err.status === 409 && /name/i.test(err.message)) {
        setNameTaken(err.message)
        showAlert()
      } else {
        setError({
          message: errorMessage(err),
          details: err instanceof ApiError ? err.fieldErrors.map((e) => fieldMessage(e.field, e.message, layout, widgets)) : [],
        })
        showAlert()
      }
    }
  }

  return (
    <>
      <PageHeader
        eyebrow={
          <nav aria-label="Breadcrumb" className="breadcrumb dashboard-breadcrumb">
            <Link href={href('/dashboards')}>Dashboards</Link>
            <Link href={viewHref}>{initial.name}</Link>
          </nav>
        }
        title={<span className="break-anywhere">Edit {name.trim() || initial.name}</span>}
        subtitle={
          <span className="saved-details">
            <span>
              {layout.widgets.length} of {MAX_WIDGETS} charts
            </span>
            <span>Editing revision {baseRevision}</span>
            <span>{dirty ? 'Unsaved changes' : 'No changes yet'}</span>
          </span>
        }
      >
        <div className="saved-actions">
          <Link className="button button-secondary" href={viewHref}>
            Cancel
          </Link>
          <button type="button" className="button button-primary" onClick={() => void save()} disabled={saving} aria-busy={saving}>
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </PageHeader>

      <div ref={alertRef} className="form-stack dashboard-editor-alerts">
        {error && (
          <FormError>
            {error.message}
            {error.details.length > 0 && (
              <ul className="chart-error-list">
                {error.details.map((detail) => (
                  <li key={detail}>{detail}</li>
                ))}
              </ul>
            )}
          </FormError>
        )}
        {missingIds.size > 0 && (
          <div className={`form-alert ${submitted ? 'form-alert-error' : 'dashboard-missing-note'}`} role={submitted ? 'alert' : 'note'}>
            <div>
              {missingIds.size === 1 ? 'One chart on this dashboard was deleted.' : `${missingIds.size} charts on this dashboard were deleted.`}{' '}
              Remove {missingIds.size === 1 ? 'it' : 'them'} before saving: a dashboard can only be saved with charts that still exist.{' '}
              <button
                type="button"
                className="button button-secondary button-small"
                onClick={() => remove(missingIds, `Removed ${missingIds.size === 1 ? 'the deleted chart' : `${missingIds.size} deleted charts`}.`)}
              >
                Remove deleted charts
              </button>
            </div>
          </div>
        )}
      </div>

      <section className="panel dashboard-editor-bar" aria-label="Dashboard">
        <TextField
          label="Name"
          value={name}
          onChange={(value) => {
            setName(value)
            setNameTaken(null)
          }}
          error={submitted || nameTaken ? nameError : null}
          maxLength={DASHBOARD_NAME_MAX + 20}
          autoComplete="off"
          fieldClassName="dashboard-name-field"
        />
        <div className="dashboard-editor-bar-actions">
          <span className="cell-secondary">
            {layout.widgets.length} of {MAX_WIDGETS} charts
          </span>
          <button type="button" className="button button-primary dashboard-add" onClick={() => setPicking(true)}>
            <PlusIcon width={16} height={16} />
            Add chart
          </button>
        </div>
      </section>

      <section className="panel dashboard-editor-panel" aria-label="Layout">
        <div className="dashboard-tabs-row">
          <div className="dashboard-tabs" role="tablist" aria-label="Layout to arrange">
            {GRIDS.map((grid) => (
              <button
                key={grid}
                type="button"
                role="tab"
                id={`${tabsId}-${grid}`}
                aria-selected={gridName === grid}
                aria-controls={`${tabsId}-panel`}
                tabIndex={gridName === grid ? 0 : -1}
                className={`dashboard-tab ${gridName === grid ? 'is-selected' : ''}`}
                onClick={() => switchGrid(grid)}
                onKeyDown={onTabKeyDown}
              >
                {GRID_LABELS[grid]}
                <span className="dashboard-tab-detail">
                  {GRID_COLUMNS[grid]} columns{grid === 'desktop' ? ', 1024 px and wider' : ', narrower screens'}
                </span>
              </button>
            ))}
          </div>
          <p className="dashboard-announcement" role="status" aria-live="polite">
            {announcement}
          </p>
        </div>
        <div className="panel-body" role="tabpanel" id={`${tabsId}-panel`} aria-labelledby={`${tabsId}-${gridName}`}>
          {layout.widgets.length === 0 ? (
            <div className="state state-empty saved-empty">
              <p className="saved-empty-title">No charts yet</p>
              <p>Add saved charts; each goes in the first free spot of both layouts, then you arrange them.</p>
              <button type="button" className="button button-primary" onClick={() => setPicking(true)}>
                Add chart
              </button>
            </div>
          ) : (
            <LayoutEditor
              key={gridName}
              grid={layout[gridName]}
              gridName={gridName}
              widgets={widgets}
              onItemsChange={setItems}
              onAnnounce={announce}
              onRemove={(id) => remove(new Set([id]), `Removed ${widgets.get(id)?.title ?? 'the chart'}.`)}
            />
          )}
        </div>
      </section>

      <ChartPicker
        open={picking}
        charts={charts}
        placed={placed}
        full={full}
        newChartHref={href('/charts/new')}
        onPick={add}
        onClose={() => setPicking(false)}
      />

      <Dialog open={conflict !== null} title="Someone else saved this dashboard" onClose={() => setConflict(null)}>
        {conflict && (
          <div className="form-stack">
            <p className="dialog-text">
              {conflict.updatedBy ?? 'Someone'} saved it
              {conflict.updatedAt ? ` on ${formatDateTimeLong(conflict.updatedAt, business.timeZone)}` : ''} while you were editing, so your
              changes weren’t saved. It is now at revision {conflict.currentRevision}; you started from revision {baseRevision}.
            </p>
            <p className="dialog-text">
              <strong>Reload latest</strong> shows their version and discards your changes. <strong>Keep editing</strong> keeps your
              changes here; saving them then replaces their version.
            </p>
            <div className="form-actions dialog-actions">
              <button
                type="button"
                className="button button-secondary"
                onClick={() => {
                  setBaseRevision(conflict.currentRevision)
                  setConflict(null)
                }}
              >
                Keep editing
              </button>
              <button type="button" className="button button-primary" onClick={onReload}>
                Reload latest
              </button>
            </div>
          </div>
        )}
      </Dialog>
    </>
  )
}

/** A 400's field error, naming the widget when the path points at one (`layout.desktop.items[2].w`). */
function fieldMessage(field: string, message: string, layout: DashboardLayout, widgets: Map<string, EditorWidget>): string {
  const match = /^layout\.(widgets|desktop\.items|mobile\.items)\[(\d+)\]/.exec(field)
  if (!match) return message
  const index = Number(match[2])
  const id = match[1] === 'widgets' ? layout.widgets[index]?.id : layout[match[1] === 'desktop.items' ? 'desktop' : 'mobile'].items[index]?.id
  const title = id ? widgets.get(id)?.title : undefined
  return title ? `${title}: ${message}` : message
}
