import type {
  CatalogFilterOption,
  ChartCatalog,
  ChartDefinition,
  ChartGroupBy,
  ChartMetric,
  ChartRange,
  ChartVisualization,
  MetricUnit,
} from '../api/charts'
import { MAX_RANGE_DAYS, PRESET_LABELS, RELATIVE_PRESETS, type RelativePreset } from '../api/savedReports'
import type { BusinessAccess, DateRange, Granularity } from '../api/types'
import { addDays, daysBetweenInclusive, formatCurrency, formatDateRange, formatNumber } from './format'

// Labels for lists that don't load the catalogue; the builder uses the catalogue's own labels.
export const VISUALIZATION_LABELS: Record<ChartVisualization, string> = {
  kpi: 'KPI tiles',
  line: 'Line chart',
  bar: 'Bar chart',
  pie: 'Pie chart',
  table: 'Table',
}

export const METRIC_LABELS: Record<ChartMetric, string> = {
  revenue: 'Revenue',
  orders: 'Orders',
  units: 'Units sold',
  average_order_value: 'Average order value',
}

export const METRIC_UNITS: Record<ChartMetric, MetricUnit> = {
  revenue: 'money',
  orders: 'count',
  units: 'count',
  average_order_value: 'money',
}

export const GROUP_BY_LABELS: Record<ChartGroupBy, string> = {
  none: 'No grouping',
  time: 'Time',
  store: 'Store',
  product: 'Product',
  category: 'Category',
}

export const GRANULARITY_LABELS: Record<Granularity, string> = { day: 'Day', week: 'Week', month: 'Month' }

export const ENGINE_LABELS: Record<string, string> = { sql: 'SQL', cube: 'Cube' }

/** "Revenue by store", "Orders by week", "Revenue, Orders": what a chart shows, in a few words. */
export function chartShape(chart: { metrics: ChartMetric[]; groupBy: ChartGroupBy; granularity?: Granularity | null }): string {
  const metrics = chart.metrics.map((m) => METRIC_LABELS[m] ?? m).join(', ')
  if (chart.groupBy === 'none') return metrics
  if (chart.groupBy === 'time') return `${metrics} by ${chart.granularity ?? 'time'}`
  return `${metrics} by ${GROUP_BY_LABELS[chart.groupBy]?.toLowerCase() ?? chart.groupBy}`
}

// ---- Permissions -------------------------------------------------------------------------------

export interface ChartPermissions {
  /** Charts belong to members: not in the anonymous public demo (the API answers 401). */
  available: boolean
  /** Build, preview, save, duplicate and delete: verified OWNER or ADMIN (`require(Role.ADMIN)`). */
  canManage: boolean
  /** An OWNER or ADMIN who could manage charts once their email address is verified. */
  needsVerification: boolean
}

/** What the current user may do with charts. The API enforces the same rules (contract §4). */
export function chartPermissions(access: BusinessAccess): ChartPermissions {
  const admin = access.role === 'OWNER' || access.role === 'ADMIN'
  return {
    available: access.role !== 'DEMO',
    canManage: admin && access.emailVerified && !access.readOnly,
    needsVerification: admin && !access.emailVerified,
  }
}

// ---- Values ------------------------------------------------------------------------------------

/** A metric value in its unit: money in the business currency, counts as whole numbers. */
export function formatMetric(value: number | undefined, unit: MetricUnit, currency: string, options: { compact?: boolean } = {}): string {
  if (value === undefined || value === null) return '—'
  if (unit === 'money') return formatCurrency(value, currency, options)
  if (options.compact && Math.abs(value) >= 10_000) {
    return new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(value)
  }
  return formatNumber(value)
}

// ---- Dates -------------------------------------------------------------------------------------

/** "Last 90 days (rolling)" or "Fixed dates". */
export function chartRangeLabel(range: ChartRange): string {
  if (range.type === 'fixed') return 'Fixed dates'
  return `${PRESET_LABELS[range.preset] ?? range.preset} (rolling)`
}

/** How a rolling period is worked out; the API resolves it the same way (contract §1). */
export function chartRollingExplanation(timeZone: string): string {
  return `Rolling periods are recalculated from today’s date in ${timeZone} every time the chart runs.`
}

/**
 * "Last 90 days (rolling) · Jul 3 – Sep 30, 2026" when the resolved period is known; fixed dates
 * always show their own dates.
 */
export function chartRangeDescription(range: ChartRange, period?: DateRange): string {
  const label = chartRangeLabel(range)
  const dates = range.type === 'fixed' && range.from && range.to ? range : period
  return dates ? `${label} · ${formatDateRange(dates.from, dates.to)}` : label
}

// ---- Catalogue rules ---------------------------------------------------------------------------

/** Contract defaults, used when the catalogue's `limits` doesn't name a value. */
export const CHART_DEFAULTS = {
  titleMax: 120,
  limitMax: 50,
  limitDefault: 10,
  filterMax: 50,
  maxRangeDays: MAX_RANGE_DAYS,
  dayRangeDays: 366,
  weekRangeDays: 1098,
}

/** A catalogue limit under any of its likely names, else the contract default. */
export function catalogLimit(catalog: ChartCatalog, names: string[], fallback: number): number {
  for (const name of names) {
    const value = catalog.limits?.[name]
    if (typeof value === 'number' && Number.isFinite(value) && value > 0) return value
  }
  return fallback
}

export function chartLimits(catalog: ChartCatalog) {
  return {
    titleMax: catalogLimit(catalog, ['titleMaxLength', 'maxTitleLength', 'titleMax'], CHART_DEFAULTS.titleMax),
    limitMax: catalogLimit(catalog, ['maxLimit', 'limitMax', 'maxGroups'], CHART_DEFAULTS.limitMax),
    limitDefault: catalogLimit(catalog, ['defaultLimit', 'limitDefault'], CHART_DEFAULTS.limitDefault),
    filterMax: catalogLimit(catalog, ['maxFilterValues', 'filterMax', 'maxFilterEntries'], CHART_DEFAULTS.filterMax),
    maxRangeDays: catalogLimit(catalog, ['maxRangeDays', 'rangeDaysMax'], CHART_DEFAULTS.maxRangeDays),
    dayRangeDays: catalogLimit(catalog, ['maxDayRangeDays', 'dayGranularityMaxDays'], CHART_DEFAULTS.dayRangeDays),
    weekRangeDays: catalogLimit(catalog, ['maxWeekRangeDays', 'weekGranularityMaxDays'], CHART_DEFAULTS.weekRangeDays),
  }
}

export type ChartLimits = ReturnType<typeof chartLimits>

function ruleReason(
  catalog: ChartCatalog,
  combination: { visualization?: ChartVisualization; groupBy?: ChartGroupBy; metric?: ChartMetric },
): string | null {
  for (const rule of catalog.rules ?? []) {
    if (rule.allowed !== false) continue
    // A rule about a field this combination doesn't fix yet can't decide it.
    if (rule.visualization && rule.visualization !== combination.visualization) continue
    if (rule.groupBy && rule.groupBy !== combination.groupBy) continue
    if (rule.metric && rule.metric !== combination.metric) continue
    if (!rule.visualization && !rule.groupBy && !rule.metric) continue
    return rule.reason
  }
  return null
}

function visualizationOf(catalog: ChartCatalog, key: ChartVisualization) {
  return catalog.visualizations.find((v) => v.key === key)
}

function labelOf(catalog: ChartCatalog, kind: 'metric' | 'dimension' | 'visualization', key: string): string {
  if (kind === 'metric') return catalog.metrics.find((m) => m.key === key)?.label ?? METRIC_LABELS[key as ChartMetric] ?? key
  if (kind === 'dimension') return catalog.dimensions.find((d) => d.key === key)?.label ?? GROUP_BY_LABELS[key as ChartGroupBy] ?? key
  return visualizationOf(catalog, key as ChartVisualization)?.label ?? VISUALIZATION_LABELS[key as ChartVisualization] ?? key
}

function joinOr(items: string[]): string {
  if (items.length <= 1) return items.join('')
  return `${items.slice(0, -1).join(', ')} or ${items[items.length - 1]}`
}

/** Why `groupBy` can't be used with `visualization` (null: it can). */
export function groupByUnavailable(catalog: ChartCatalog, visualization: ChartVisualization, groupBy: ChartGroupBy): string | null {
  const viz = visualizationOf(catalog, visualization)
  if (!viz) return null
  const fromRule = ruleReason(catalog, { visualization, groupBy })
  if (fromRule) return fromRule
  if (!viz.groupBy.includes(groupBy)) {
    const accepted = viz.groupBy.map((g) => labelOf(catalog, 'dimension', g).toLowerCase())
    return viz.groupBy.length === 1 && viz.groupBy[0] === 'none'
      ? `Not with ${viz.label}: it shows totals, without grouping.`
      : `Not with ${viz.label}: it groups by ${joinOr(accepted)}.`
  }
  return null
}

/**
 * Why `metric` can't be added to a chart of `visualization` grouped by `groupBy` (null: it can).
 * `selected` is the current choice, for the per-visualization maximum.
 */
export function metricUnavailable(
  catalog: ChartCatalog,
  visualization: ChartVisualization,
  groupBy: ChartGroupBy,
  metric: ChartMetric,
  selected: ChartMetric[] = [],
): string | null {
  const fromRule =
    ruleReason(catalog, { visualization, groupBy, metric }) ?? ruleReason(catalog, { groupBy, metric }) ?? ruleReason(catalog, { metric, visualization })
  if (fromRule) return fromRule
  const definition = catalog.metrics.find((m) => m.key === metric)
  const viz = visualizationOf(catalog, visualization)
  if (visualization === 'pie' && definition && !definition.additive) {
    return `A pie shows shares of a total, so it needs a metric that adds up across groups; ${definition.label.toLowerCase()} doesn’t.`
  }
  if (viz && viz.maxMetrics > 1 && !selected.includes(metric) && selected.length >= viz.maxMetrics) {
    return `${viz.label}: at most ${viz.maxMetrics} metrics. Clear one to choose this.`
  }
  return null
}

/** The time bucket sizes offered for `groupBy: time`. */
export function granularitiesOf(catalog: ChartCatalog): Granularity[] {
  const time = catalog.dimensions.find((d) => d.key === 'time')
  return time?.granularities?.length ? time.granularities : ['day', 'week', 'month']
}

/** Presets offered for rolling dates: the catalogue's, else the saved-report presets. */
export function presetsOf(catalog: ChartCatalog): { key: RelativePreset; label: string }[] {
  return catalog.presets?.length ? catalog.presets : RELATIVE_PRESETS.map((p) => ({ key: p.value, label: p.label }))
}

export interface FilterOption {
  value: string
  label: string
}

/** Options of one catalogue filter (`storeIds`, `categories`), normalised to value/label pairs. */
export function filterOptions(catalog: ChartCatalog, key: string): FilterOption[] | null {
  const filter = catalog.filters?.find((f) => f.key === key)
  if (!filter?.options) return null
  return filter.options.map((option: CatalogFilterOption) => {
    if (typeof option === 'string') return { value: option, label: option }
    if ('value' in option) return { value: String(option.value), label: option.label }
    return { value: String(option.id), label: option.name }
  })
}

/**
 * Makes `definition` valid for the catalogue after the visualization or grouping changed, keeping
 * as much of the user's choice as possible. Returns what had to change, to tell the user.
 */
export function reconcile(catalog: ChartCatalog, definition: ChartDefinition, limits: ChartLimits): { definition: ChartDefinition; changes: string[] } {
  const next = { ...definition }
  const changes: string[] = []
  const viz = visualizationOf(catalog, next.visualization)
  if (!viz) return { definition: next, changes }

  if (groupByUnavailable(catalog, next.visualization, next.groupBy)) {
    const allowed = viz.groupBy.find((g) => !groupByUnavailable(catalog, next.visualization, g))
    if (allowed) {
      changes.push(`Grouping changed to ${labelOf(catalog, 'dimension', allowed).toLowerCase()}`)
      next.groupBy = allowed
    }
  }

  let metrics = next.metrics.filter((m) => !metricUnavailable(catalog, next.visualization, next.groupBy, m))
  metrics = metrics.slice(0, viz.maxMetrics)
  if (metrics.length === 0) {
    const first = catalog.metrics.find((m) => !metricUnavailable(catalog, next.visualization, next.groupBy, m.key))
    if (first) metrics = [first.key]
  }
  const dropped = next.metrics.filter((m) => !metrics.includes(m))
  if (dropped.length > 0) {
    changes.push(`${joinOr(dropped.map((m) => labelOf(catalog, 'metric', m)))} removed`)
  }
  next.metrics = metrics

  if (next.groupBy === 'time') {
    const offered = granularitiesOf(catalog)
    if (!next.granularity || !offered.includes(next.granularity)) next.granularity = offered.includes('week') ? 'week' : offered[0]
  } else {
    next.granularity = null
  }
  next.limit = isRanked(next.groupBy) ? (next.limit ?? limits.limitDefault) : null
  return { definition: next, changes }
}

/** Grouped by store, product or category: the chart shows the top `limit` groups. */
export function isRanked(groupBy: ChartGroupBy): boolean {
  return groupBy === 'store' || groupBy === 'product' || groupBy === 'category'
}

/** A new chart: revenue by week over the last 90 days (valid for any catalogue that has a line). */
export function newDefinition(catalog: ChartCatalog, limits: ChartLimits): ChartDefinition {
  const engine = catalog.engines.includes(catalog.defaultEngine) ? catalog.defaultEngine : 'sql'
  const base: ChartDefinition = {
    schemaVersion: 1,
    title: '',
    visualization: catalog.visualizations.some((v) => v.key === 'line') ? 'line' : catalog.visualizations[0].key,
    metrics: ['revenue'],
    groupBy: 'time',
    granularity: 'week',
    range: { type: 'relative', preset: 'last_90_days' },
    filters: { storeIds: [], categories: [], productIds: [] },
    limit: null,
    engine,
  }
  return reconcile(catalog, base, limits).definition
}

export type ChartFieldErrors = Partial<Record<string, string>>

/**
 * Client checks that mirror the server's (contract §1–3), keyed like the server's field paths, so
 * mistakes show while editing. The server still validates everything.
 */
export function validateDefinition(definition: ChartDefinition, limits: ChartLimits, { forSave }: { forSave: boolean }): ChartFieldErrors {
  const errors: ChartFieldErrors = {}
  const title = definition.title.trim()
  if (forSave && !title) errors.title = 'Enter a title.'
  else if (title.length > limits.titleMax) errors.title = `Use at most ${limits.titleMax} characters.`
  if (definition.metrics.length === 0) errors.metrics = 'Choose at least one metric.'
  if (definition.range.type === 'fixed') {
    const { from, to } = definition.range
    if (!from) errors['range.from'] = 'Choose a start date.'
    if (!to) errors['range.to'] = 'Choose an end date.'
    else if (from && from > to) errors['range.to'] = 'The end date must be on or after the start date.'
    else if (from) {
      const days = daysBetweenInclusive(from, to)
      if (days > limits.maxRangeDays) errors['range.to'] = `The dates may cover at most ${formatNumber(limits.maxRangeDays)} days.`
      else if (definition.granularity === 'day' && days > limits.dayRangeDays) {
        errors.granularity = `Days can be shown for at most ${limits.dayRangeDays} days; choose weeks or months, or shorter dates.`
      } else if (definition.granularity === 'week' && days > limits.weekRangeDays) {
        errors.granularity = `Weeks can be shown for at most ${formatNumber(limits.weekRangeDays)} days; choose months.`
      }
    }
  }
  if (definition.limit !== null && (!Number.isInteger(definition.limit) || definition.limit < 1 || definition.limit > limits.limitMax)) {
    errors.limit = `Enter a whole number from 1 to ${limits.limitMax}.`
  }
  for (const key of ['storeIds', 'categories', 'productIds'] as const) {
    if (definition.filters[key].length > limits.filterMax) errors[`filters.${key}`] = `Choose at most ${limits.filterMax}.`
  }
  return errors
}

/**
 * The server's field path as the builder's field key: PUT bodies nest the definition
 * (`definition.metrics`), list paths may carry an index (`filters.storeIds[3]`).
 */
export function fieldKey(path: string): string {
  return path.replace(/^definition\./, '').replace(/\[\d+\]$/, '')
}

/** The last 30 days ending on `today`, to prefill fixed dates. */
export function defaultFixedRange(today: string): DateRange {
  return { from: addDays(today, -29), to: today }
}

// ---- Colours (dataviz skill: fixed categorical order, validated against the white surface) -----

/**
 * Categorical slots in fixed order; the first five validate adjacent-pair CVD and normal-vision
 * separation on #ffffff. Slots 3–5 are below 3:1, so pies always list values beside the swatches
 * and every chart has a table view.
 */
export const CATEGORICAL = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4']
/** The folded "Other" slice: a neutral, never a ninth hue. */
export const OTHER_COLOR = '#b4bccb'
/** One series: categorical slot 1. */
export const SERIES = CATEGORICAL[0]
export const GRID = '#e6e9ef'
export const AXIS_TEXT = '#6b7688'
export const SURFACE = '#ffffff'
