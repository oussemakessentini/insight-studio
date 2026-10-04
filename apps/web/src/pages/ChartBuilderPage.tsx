import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import {
  chartsApi,
  type Chart,
  type ChartCatalog,
  type ChartDefinition,
  type ChartGroupBy,
  type ChartMetric,
  type ChartResult,
  type ChartVisualization,
} from '../api/charts'
import type { RelativePreset } from '../api/savedReports'
import type { DashboardContext, Granularity } from '../api/types'
import { ChartPanel } from '../components/charts/ChartView'
import { ProductPicker } from '../components/charts/ProductPicker'
import { FormError, SelectField, SubmitButton, TextField } from '../components/Form'
import { Link } from '../components/Link'
import { PageHeader } from '../components/PageHeader'
import { SeePlansLink } from '../components/SeePlans'
import { ErrorState, Panel, Skeleton, SkeletonRows } from '../components/Panel'
import { useApi } from '../hooks/useApi'
import { useTouched } from '../hooks/useTouched'
import {
  chartLimits,
  chartRollingExplanation,
  chartShape,
  defaultFixedRange,
  ENGINE_LABELS,
  fieldKey,
  filterOptions,
  granularitiesOf,
  GRANULARITY_LABELS,
  groupByUnavailable,
  isRanked,
  metricUnavailable,
  newDefinition,
  presetsOf,
  reconcile,
  validateDefinition,
  type ChartFieldErrors,
  type FilterOption,
} from '../lib/charts'
import { planLimitOf } from '../lib/billing'
import { formatDateTimeLong } from '../lib/format'
import { navigate } from '../lib/router'
import { errorMessage } from '../lib/validation'
import '../styles/accounts.css'
import '../styles/reports.css'
import '../styles/charts.css'
import type { PageProps } from './types'

/** `/charts/new` (chartId null) and `/charts/{id}/edit`: loads the catalogue (and the chart), then builds. */
export function ChartBuilderPage({ chartId, context, href }: PageProps & { chartId: number | null }) {
  const catalog = useApi('chart-catalog', (signal) => chartsApi.catalog(signal))
  const chart = useApi(`chart-edit|${chartId ?? 'new'}`, (signal) => (chartId === null ? Promise.resolve(null) : chartsApi.get(chartId, signal)))
  const listHref = href('/charts')
  const failed = catalog.error ?? chart.error

  const header = (
    <PageHeader
      eyebrow={
        <nav aria-label="Breadcrumb" className="breadcrumb">
          <Link href={listHref}>Charts</Link>
          {chartId !== null && chart.data && (
            <>
              <span aria-hidden="true">/</span>
              <Link href={href(`/charts/${chartId}`)} className="break-anywhere">
                {chart.data.title}
              </Link>
            </>
          )}
        </nav>
      }
      title={chartId === null ? 'New chart' : 'Edit chart'}
      subtitle={
        chartId === null
          ? 'Choose what to show, preview it with your data, then save it for everyone in the business.'
          : 'Saving keeps the previous version in the chart’s revisions.'
      }
    />
  )

  if (failed) {
    const notFound = failed instanceof ApiError && failed.status === 404 && chart.error === failed
    return (
      <>
        {header}
        <div className="panel page-error">
          <ErrorState
            message={notFound ? 'This chart doesn’t exist any more, or it belongs to another business.' : failed.message}
            onRetry={notFound ? undefined : () => (catalog.error ? catalog.retry() : chart.retry())}
          />
        </div>
      </>
    )
  }
  if (!catalog.data || chart.data === undefined) {
    return (
      <>
        {header}
        <div className="chart-builder">
          <Panel title="Chart settings">
            <SkeletonRows rows={8} />
          </Panel>
          <Panel title="Preview">
            <Skeleton height={300} />
          </Panel>
        </div>
      </>
    )
  }
  return (
    <>
      {header}
      <ChartBuilder catalog={catalog.data} chart={chart.data} context={context} href={href} />
    </>
  )
}

interface PreviewState {
  data: ChartResult | undefined
  error: Error | undefined
  loading: boolean
  /** The definition the preview ran, to tell when the settings moved on. */
  ran: string | null
}

const NO_PREVIEW: PreviewState = { data: undefined, error: undefined, loading: false, ran: null }

/**
 * Whether the builder currently shows a field for the server's error path; errors on any other
 * path (or a field hidden by the current settings) are listed in the form or preview message.
 */
function fieldShown(key: string, definition: ChartDefinition, showEngine: boolean): boolean {
  switch (key) {
    case 'title':
    case 'visualization':
    case 'metrics':
    case 'groupBy':
    case 'range':
    case 'filters.storeIds':
    case 'filters.categories':
    case 'filters.productIds':
      return true
    case 'range.preset':
      return definition.range.type === 'relative'
    case 'range.from':
    case 'range.to':
      return definition.range.type === 'fixed'
    case 'granularity':
      return definition.groupBy === 'time'
    case 'limit':
      return isRanked(definition.groupBy)
    case 'engine':
      return showEngine
    default:
      return false
  }
}

function ChartBuilder({
  catalog,
  chart,
  context,
  href,
}: {
  catalog: ChartCatalog
  chart: Chart | null
  context: DashboardContext
  href: (path: string) => string
}) {
  const { business } = context
  const limits = chartLimits(catalog)
  const [definition, setDefinition] = useState<ChartDefinition>(() => chart?.definition ?? newDefinition(catalog, limits))
  const [baseRevision, setBaseRevision] = useState(chart?.revision ?? null)
  const [adjusted, setAdjusted] = useState<string | null>(null)
  const [attempted, setAttempted] = useState(false)
  const [serverErrors, setServerErrors] = useState<ChartFieldErrors>({})
  const [formError, setFormError] = useState<ReactNode>(null)
  const [conflict, setConflict] = useState<Chart | null>(null)
  const [saving, setSaving] = useState(false)
  const [preview, setPreview] = useState<PreviewState>(NO_PREVIEW)
  // Preview refusals about settings without a field of their own.
  const [previewProblems, setPreviewProblems] = useState<string[]>([])
  const previewAbort = useRef<AbortController | null>(null)
  const formRef = useRef<HTMLFormElement>(null)
  const previewRef = useRef<HTMLDivElement>(null)

  // A refusal is announced at the top of the form; bring it into view (Save sits at the bottom).
  useEffect(() => {
    if (conflict || formError) formRef.current?.scrollIntoView({ block: 'start', behavior: 'smooth' })
  }, [conflict, formError])
  const touched = useTouched<'title'>()
  const ids = { viz: useId(), groupBy: useId(), metrics: useId(), dates: useId() }

  const clientErrors = validateDefinition(definition, limits, { forSave: true })
  const errorFor = (field: string): string | null => {
    const server = serverErrors[field]
    if (server) return server
    // The title only matters for saving (a preview runs without one).
    if (field === 'title' && !touched.shows('title')) return null
    if (field !== 'title' && !attempted) return null
    return clientErrors[field] ?? null
  }

  /** Every edit goes through here: it keeps the definition valid for the catalogue. */
  const update = (patch: Partial<ChartDefinition>, reconcileAfter = false) => {
    setServerErrors({})
    const next = { ...definition, ...patch }
    if (!reconcileAfter) {
      setDefinition(next)
      return
    }
    const result = reconcile(catalog, next, limits)
    setAdjusted(result.changes.length ? `${result.changes.join('; ')} to fit this chart.` : null)
    setDefinition(result.definition)
  }

  const runPreview = async () => {
    setAttempted(true)
    const errors = validateDefinition(definition, limits, { forSave: false })
    if (Object.keys(errors).length > 0) return
    previewAbort.current?.abort()
    const controller = new AbortController()
    previewAbort.current = controller
    // The title isn't needed to preview; the server may still check it, so send a placeholder.
    const body = { ...definition, title: definition.title.trim() || 'Untitled chart' }
    const ran = JSON.stringify(definition)
    setPreview((prev) => ({ ...prev, error: undefined, loading: true, ran }))
    setPreviewProblems([])
    try {
      const data = await chartsApi.preview(body, controller.signal)
      setPreview({ data, error: undefined, loading: false, ran })
      showPreview()
    } catch (err) {
      if (controller.signal.aborted) return
      showPreview()
      const error = err instanceof Error ? err : new Error(String(err))
      if (err instanceof ApiError && err.status === 400) {
        setPreviewProblems(applyServerErrors(err, { ignoreTitle: !definition.title.trim() }).other)
      }
      setPreview({ data: undefined, error, loading: false, ran })
    }
  }

  /** Where the preview sits below the form (narrow screens), scroll to it once it answered. */
  const showPreview = () => {
    if (window.matchMedia('(max-width: 1200px)').matches) {
      requestAnimationFrame(() => previewRef.current?.scrollIntoView({ block: 'start', behavior: 'smooth' }))
    }
  }

  const applyServerErrors = (err: ApiError, { ignoreTitle = false } = {}) => {
    const fields: ChartFieldErrors = {}
    const other: string[] = []
    for (const e of err.fieldErrors) {
      const key = fieldKey(e.field)
      if (ignoreTitle && key === 'title') continue
      const shown = key === 'range.type' ? 'range' : key
      if (fieldShown(shown, definition, showEngine)) fields[shown] ??= e.message
      else other.push(e.message)
    }
    setServerErrors(fields)
    return { fields, other }
  }

  const save = async (event: FormEvent) => {
    event.preventDefault()
    setAttempted(true)
    touched.touchAll()
    setFormError(null)
    setConflict(null)
    if (Object.keys(clientErrors).length > 0) {
      setFormError('Check the highlighted settings.')
      return
    }
    setSaving(true)
    const body = { ...definition, title: definition.title.trim() }
    try {
      const saved = chart && baseRevision !== null ? await chartsApi.update(chart.id, body, baseRevision) : await chartsApi.create(body)
      navigate(href(`/charts/${saved.id}`))
    } catch (err) {
      setSaving(false)
      if (err instanceof ApiError && err.status === 400) {
        const { fields, other } = applyServerErrors(err)
        setFormError(
          <>
            {err.message}
            {other.length > 0 && (
              <ul className="chart-error-list">
                {other.map((m) => (
                  <li key={m}>{m}</li>
                ))}
              </ul>
            )}
            {Object.keys(fields).length > 0 && other.length === 0 && ' The settings concerned are marked below.'}
          </>,
        )
        return
      }
      if (err instanceof ApiError && err.status === 409 && chart) {
        // Either someone saved a newer revision meanwhile, or the title is taken: ask the server.
        const latest = await chartsApi.get(chart.id).catch(() => null)
        if (latest && latest.revision !== baseRevision) {
          setConflict(latest)
          return
        }
      }
      if (err instanceof ApiError && err.status === 409 && !planLimitOf(err) && /title/i.test(err.message)) {
        setServerErrors({ title: err.message })
      }
      setFormError(
        <>
          {errorMessage(err)}
          <SeePlansLink error={err} />
        </>,
      )
    }
  }

  const reloadLatest = (latest: Chart) => {
    setDefinition(latest.definition)
    setBaseRevision(latest.revision)
    setConflict(null)
    setFormError(null)
    setServerErrors({})
    setAdjusted(null)
    setPreview(NO_PREVIEW)
  }

  const viz = catalog.visualizations.find((v) => v.key === definition.visualization)
  const multiMetric = (viz?.maxMetrics ?? 1) > 1
  const previewStale = preview.ran !== null && preview.ran !== JSON.stringify(definition) && !preview.loading
  const storeOptions: FilterOption[] =
    filterOptions(catalog, 'storeIds') ?? context.stores.map((s) => ({ value: String(s.id), label: s.name }))
  const categoryOptions = filterOptions(catalog, 'categories') ?? []
  const showEngine = catalog.engines.includes('cube')

  return (
    <div className="chart-builder">
      <form ref={formRef} className="chart-builder-form" onSubmit={(e) => void save(e)} noValidate aria-busy={saving}>
        {conflict && (
          <div className="form-alert form-alert-error chart-conflict" role="alert">
            <div>
              <p>
                <strong>This chart was changed by someone else</strong>
                {conflict.updatedBy ? ` (${conflict.updatedBy}, ` : ' ('}
                {formatDateTimeLong(conflict.updatedAt, business.timeZone)}), so your changes weren’t saved. It is now at revision{' '}
                {conflict.revision}; you started from revision {baseRevision}.
              </p>
              <p>Load the latest version to continue from it. Your unsaved changes here will be replaced.</p>
              <div className="form-actions">
                <button type="button" className="button button-primary button-small" onClick={() => reloadLatest(conflict)}>
                  Load the latest version
                </button>
                <Link className="button button-secondary button-small" href={href(`/charts/${conflict.id}`)} target="_blank" rel="noopener">
                  Open it in a new tab
                </Link>
              </div>
            </div>
          </div>
        )}
        {formError && <FormError>{formError}</FormError>}

        <Panel title="Chart settings">
          <div className="form-stack">
            <TextField
              label="Title"
              name="chart-title"
              autoComplete="off"
              value={definition.title}
              maxLength={limits.titleMax + 20}
              onChange={(title) => update({ title })}
              onBlur={(e) => touched.touch('title', e.currentTarget.value)}
              error={errorFor('title')}
              hint="Shown in the chart list, for example “Revenue by store, last 90 days”. Unique in the business."
              disabled={saving}
            />

            <fieldset className="choice-group" disabled={saving} aria-describedby={errorFor('visualization') ? `${ids.viz}-error` : undefined}>
              <legend className="form-label">Visualization</legend>
              <div className="chart-choices chart-choices-viz">
                {catalog.visualizations.map((v) => (
                  <label key={v.key} className="choice">
                    <input
                      type="radio"
                      name={ids.viz}
                      checked={definition.visualization === v.key}
                      onChange={() => update({ visualization: v.key }, true)}
                    />
                    <span>
                      <span className="choice-label">{v.label}</span>
                      <span className="choice-hint">{visualizationHint(v.key, v.minMetrics, v.maxMetrics)}</span>
                    </span>
                  </label>
                ))}
              </div>
              <p className="form-hint chart-adjusted" aria-live="polite">
                {adjusted}
              </p>
              {errorFor('visualization') && (
                <p className="form-error" id={`${ids.viz}-error`}>
                  {errorFor('visualization')}
                </p>
              )}
            </fieldset>

            <fieldset className="choice-group" disabled={saving}>
              <legend className="form-label">Group by</legend>
              <div className="chart-choices">
                {catalog.dimensions.map((d) => {
                  const reason = groupByUnavailable(catalog, definition.visualization, d.key)
                  const reasonId = `${ids.groupBy}-${d.key}`
                  return (
                    <label key={d.key} className={`choice ${reason ? 'is-disabled' : ''}`}>
                      <input
                        type="radio"
                        name={ids.groupBy}
                        checked={definition.groupBy === d.key}
                        disabled={Boolean(reason)}
                        aria-describedby={reason ? reasonId : undefined}
                        onChange={() => update({ groupBy: d.key as ChartGroupBy }, true)}
                      />
                      <span>
                        <span className="choice-label">{d.label}</span>
                        {reason && (
                          <span className="choice-hint" id={reasonId}>
                            {reason}
                          </span>
                        )}
                      </span>
                    </label>
                  )
                })}
              </div>
              {errorFor('groupBy') && <p className="form-error">{errorFor('groupBy')}</p>}
            </fieldset>

            {definition.groupBy === 'time' && (
              <SelectField
                label="Each point is a"
                value={definition.granularity ?? 'week'}
                onChange={(g) => update({ granularity: g as Granularity })}
                error={errorFor('granularity')}
                hint="Days for up to a year of dates, weeks for up to three years."
                disabled={saving}
              >
                {granularitiesOf(catalog).map((g) => (
                  <option key={g} value={g}>
                    {GRANULARITY_LABELS[g]}
                  </option>
                ))}
              </SelectField>
            )}

            <fieldset className="choice-group" disabled={saving}>
              <legend className="form-label">
                {multiMetric ? `Metrics (${viz?.minMetrics ?? 1} to ${viz?.maxMetrics})` : 'Metric'}
              </legend>
              <div className="chart-choices">
                {catalog.metrics.map((m) => {
                  const checked = definition.metrics.includes(m.key)
                  const reason = metricUnavailable(catalog, definition.visualization, definition.groupBy, m.key, definition.metrics)
                  const reasonId = `${ids.metrics}-${m.key}`
                  return (
                    <label key={m.key} className={`choice ${reason && !checked ? 'is-disabled' : ''}`}>
                      <input
                        type={multiMetric ? 'checkbox' : 'radio'}
                        name={ids.metrics}
                        checked={checked}
                        disabled={Boolean(reason) && !checked}
                        aria-describedby={reason ? reasonId : undefined}
                        onChange={() => update({ metrics: toggleMetric(definition.metrics, m.key, multiMetric) })}
                      />
                      <span>
                        <span className="choice-label">{m.label}</span>
                        <span className="choice-hint" id={reason ? reasonId : undefined}>
                          {reason ?? (m.unit === 'money' ? `In ${business.currency}` : 'A count')}
                        </span>
                      </span>
                    </label>
                  )
                })}
              </div>
              {errorFor('metrics') && <p className="form-error">{errorFor('metrics')}</p>}
            </fieldset>

            {isRanked(definition.groupBy) && (
              <TextField
                label="Groups shown"
                type="number"
                inputMode="numeric"
                min={1}
                max={limits.limitMax}
                step={1}
                value={definition.limit === null || Number.isNaN(definition.limit) ? '' : String(definition.limit)}
                onChange={(v) => update({ limit: v === '' ? NaN : Number(v) })}
                error={errorFor('limit')}
                hint={`The top 1 to ${limits.limitMax} by ${definition.metrics[0] ? catalog.metrics.find((m) => m.key === definition.metrics[0])?.label.toLowerCase() : 'the first metric'}. Totals always cover every group.`}
                disabled={saving}
                fieldClassName="chart-limit"
              />
            )}
          </div>
        </Panel>

        <Panel title="Dates and filters">
          <div className="form-stack">
            <DatesField
              definition={definition}
              catalog={catalog}
              timeZone={business.timeZone}
              today={context.dataRange?.to ?? new Date().toISOString().slice(0, 10)}
              disabled={saving}
              radioName={ids.dates}
              errorFor={errorFor}
              onChange={(range) => update({ range })}
            />

            <FilterChecklist
              label="Stores"
              allLabel="All stores"
              options={storeOptions}
              selected={definition.filters.storeIds.map(String)}
              missingLabel={(id) => `Store ${id} (no longer available)`}
              max={limits.filterMax}
              error={errorFor('filters.storeIds')}
              disabled={saving}
              onChange={(values) => update({ filters: { ...definition.filters, storeIds: values.map(Number) } })}
            />
            <FilterChecklist
              label="Categories"
              allLabel="All categories"
              options={categoryOptions}
              selected={definition.filters.categories}
              missingLabel={(name) => `${name} (no products left)`}
              max={limits.filterMax}
              error={errorFor('filters.categories')}
              disabled={saving}
              onChange={(categories) => update({ filters: { ...definition.filters, categories } })}
            />
            <ProductPicker
              selected={definition.filters.productIds}
              max={limits.filterMax}
              disabled={saving}
              error={errorFor('filters.productIds')}
              onChange={(productIds) => update({ filters: { ...definition.filters, productIds } })}
            />

            {showEngine && (
              <SelectField
                label="Engine"
                value={definition.engine}
                onChange={(engine) => update({ engine: engine as ChartDefinition['engine'] })}
                error={errorFor('engine')}
                hint="Both give the same figures; Cube answers from pre-aggregated data."
                disabled={saving}
              >
                {catalog.engines.map((e) => (
                  <option key={e} value={e}>
                    {ENGINE_LABELS[e] ?? e}
                  </option>
                ))}
              </SelectField>
            )}
          </div>
        </Panel>

        <div className="form-actions chart-builder-actions">
          <button type="button" className="button button-secondary" onClick={() => void runPreview()} disabled={saving || preview.loading} aria-busy={preview.loading}>
            {preview.loading ? 'Previewing…' : 'Preview'}
          </button>
          <SubmitButton busy={saving} busyLabel="Saving…">
            {chart ? 'Save new revision' : 'Save chart'}
          </SubmitButton>
          <Link className="button button-secondary" href={href(chart ? `/charts/${chart.id}` : '/charts')}>
            Cancel
          </Link>
        </div>
      </form>

      <div ref={previewRef} className="chart-builder-preview">
        <ChartPanel
          title={definition.title.trim() || 'Preview'}
          subtitle={chartShape(definition)}
          visualization={definition.visualization}
          data={preview.data}
          error={preview.error}
          loading={preview.loading}
          retry={preview.error instanceof ApiError && preview.error.status === 400 ? undefined : () => void runPreview()}
          errorHint={
            preview.error instanceof ApiError && preview.error.status === 400 ? (
              <>
                {previewProblems.length > 0 && (
                  <ul className="chart-error-list chart-error-problems">
                    {previewProblems.map((m) => (
                      <li key={m}>{m}</li>
                    ))}
                  </ul>
                )}
                {Object.keys(serverErrors).length > 0 && <p>Fix the settings marked in the form, then preview again.</p>}
              </>
            ) : undefined
          }
          placeholder={
            <div className="state state-empty">
              <p>Choose Preview to run these settings on your data. Nothing is saved until you choose Save.</p>
            </div>
          }
          stale={
            previewStale ? (
              <p className="chart-stale" role="status">
                The settings changed since this preview. Choose Preview again to update it.
              </p>
            ) : undefined
          }
        />
      </div>
    </div>
  )
}

function visualizationHint(key: ChartVisualization, min: number, max: number): string {
  const metrics = min === max ? `${max} metric${max === 1 ? '' : 's'}` : `${min}–${max} metrics`
  switch (key) {
    case 'kpi':
      return `Headline numbers · ${metrics}`
    case 'line':
      return `A trend over time · ${metrics}`
    case 'bar':
      return `Compare periods or groups · ${metrics}`
    case 'pie':
      return `Shares of a total · ${metrics}`
    case 'table':
      return `Rows and columns · ${metrics}`
    default:
      return metrics
  }
}

function toggleMetric(selected: ChartMetric[], metric: ChartMetric, multiple: boolean): ChartMetric[] {
  if (!multiple) return [metric]
  return selected.includes(metric) ? selected.filter((m) => m !== metric) : [...selected, metric]
}

function DatesField({
  definition,
  catalog,
  timeZone,
  today,
  disabled,
  radioName,
  errorFor,
  onChange,
}: {
  definition: ChartDefinition
  catalog: ChartCatalog
  timeZone: string
  today: string
  disabled: boolean
  radioName: string
  errorFor: (field: string) => string | null
  onChange: (range: ChartDefinition['range']) => void
}) {
  const { range } = definition
  const fixed = range.type === 'fixed'
  // Switching keeps the other mode's choice so flipping back and forth loses nothing.
  const [lastFixed, setLastFixed] = useState(() => (range.type === 'fixed' ? { from: range.from, to: range.to } : defaultFixedRange(today)))
  const [lastPreset, setLastPreset] = useState<RelativePreset>(range.type === 'relative' ? range.preset : 'last_90_days')
  const presets = presetsOf(catalog)

  return (
    <fieldset className="choice-group" disabled={disabled}>
      <legend className="form-label">Dates</legend>
      <label className="choice">
        <input type="radio" name={radioName} checked={!fixed} onChange={() => onChange({ type: 'relative', preset: lastPreset })} />
        <span>
          <span className="choice-label">A rolling period</span>
          <span className="choice-hint">{chartRollingExplanation(timeZone)}</span>
        </span>
      </label>
      {range.type === 'relative' && (
        <div className="choice-detail">
          <SelectField
            label="Period"
            value={range.preset}
            onChange={(v) => {
              setLastPreset(v as RelativePreset)
              onChange({ type: 'relative', preset: v as RelativePreset })
            }}
            error={errorFor('range.preset') ?? errorFor('range')}
          >
            {presets.map((p) => (
              <option key={p.key} value={p.key}>
                {p.label}
              </option>
            ))}
          </SelectField>
        </div>
      )}
      <label className="choice">
        <input type="radio" name={radioName} checked={fixed} onChange={() => onChange({ type: 'fixed', ...lastFixed })} />
        <span>
          <span className="choice-label">Fixed dates</span>
          <span className="choice-hint">The same period every time the chart runs</span>
        </span>
      </label>
      {range.type === 'fixed' && (
        <div className="form-columns choice-detail">
          <TextField
            label="From"
            type="date"
            value={range.from}
            max={range.to || undefined}
            onChange={(from) => {
              setLastFixed({ from, to: range.to })
              onChange({ type: 'fixed', from, to: range.to })
            }}
            error={errorFor('range.from') ?? errorFor('range')}
          />
          <TextField
            label="To"
            type="date"
            value={range.to}
            min={range.from || undefined}
            onChange={(to) => {
              setLastFixed({ from: range.from, to })
              onChange({ type: 'fixed', from: range.from, to })
            }}
            error={errorFor('range.to')}
          />
        </div>
      )}
    </fieldset>
  )
}

/** A checkbox list for one filter; nothing checked means everything. */
function FilterChecklist({
  label,
  allLabel,
  options,
  selected,
  max,
  error,
  disabled,
  missingLabel,
  onChange,
}: {
  label: string
  allLabel: string
  missingLabel: (value: string) => string
  options: FilterOption[]
  selected: string[]
  max: number
  error: string | null
  disabled: boolean
  onChange: (values: string[]) => void
}) {
  const hintId = useId()
  // A saved value missing from today's options (e.g. a deleted store) stays visible so it can be removed.
  const missing = selected.filter((value) => !options.some((o) => o.value === value)).map((value) => ({ value, label: missingLabel(value) }))
  const all = [...options, ...missing]
  const full = selected.length >= max
  return (
    <fieldset className="choice-group chart-filter" disabled={disabled} aria-describedby={hintId}>
      <legend className="form-label">{label}</legend>
      <p className="form-hint" id={hintId}>
        {selected.length === 0 ? `${allLabel}. Tick some to narrow the chart.` : `${selected.length} of ${all.length} chosen.`}{' '}
        {selected.length > 0 && (
          <button type="button" className="link-button" onClick={() => onChange([])}>
            Use {allLabel.toLowerCase()}
          </button>
        )}
      </p>
      {all.length === 0 ? (
        <p className="form-hint">None yet.</p>
      ) : (
        <ul className="chart-checklist">
          {all.map((option) => {
            const checked = selected.includes(option.value)
            return (
              <li key={option.value}>
                <label className="chart-check">
                  <input
                    type="checkbox"
                    checked={checked}
                    disabled={!checked && full}
                    onChange={() => onChange(checked ? selected.filter((v) => v !== option.value) : [...selected, option.value])}
                  />
                  <span className="break-anywhere">{option.label}</span>
                </label>
              </li>
            )
          })}
        </ul>
      )}
      {full && <p className="form-hint">At most {max}. Untick one to choose another.</p>}
      {error && <p className="form-error">{error}</p>}
    </fieldset>
  )
}
