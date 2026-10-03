import { useState, type ReactNode } from 'react'
import {
  Area,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ComposedChart,
  LabelList,
  Line,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { ChartColumn, ChartMetric, ChartResult, ChartRow, ChartVisualization, MetricUnit } from '../../api/charts'
import type { Granularity } from '../../api/types'
import {
  AXIS_TEXT,
  CATEGORICAL,
  GRID,
  GROUP_BY_LABELS,
  METRIC_UNITS,
  OTHER_COLOR,
  SERIES,
  SURFACE,
  formatMetric,
} from '../../lib/charts'
import { formatBucketLabel, formatBucketTick, formatDateRange, formatPercent } from '../../lib/format'
import { EmptyState, ErrorState, Panel, Skeleton } from '../Panel'
import { Segmented } from '../TrendChart'

/** The metric columns of a result, in the definition's order. */
function metricColumns(result: ChartResult): (ChartColumn & { key: ChartMetric; unit: MetricUnit })[] {
  return result.columns
    .filter((c) => c.type === 'metric')
    .map((c) => ({ ...c, key: c.key as ChartMetric, unit: c.unit ?? METRIC_UNITS[c.key as ChartMetric] ?? 'count' }))
}

/** A row's dimension as people read it: "Week of Sep 7, 2026" for time buckets, the group name otherwise. */
function rowLabel(row: ChartRow, result: ChartResult): string {
  if (result.groupBy === 'time' && result.granularity) return formatBucketLabel(row.key, result.granularity)
  return row.label
}

function isEmpty(result: ChartResult): boolean {
  const metrics = metricColumns(result)
  return result.rows.length === 0 || result.rows.every((row) => metrics.every((m) => !row.values[m.key]))
}

interface ChartPanelProps {
  title: string
  subtitle?: string
  visualization: ChartVisualization
  data: ChartResult | undefined
  error: Error | undefined
  loading: boolean
  retry?: () => void
  /** Shown under an error, e.g. how to fix a definition that no longer validates. */
  errorHint?: ReactNode
  /** Before anything ran (the builder's preview). */
  placeholder?: ReactNode
  /** The result no longer matches what it describes (the builder changed since the preview). */
  stale?: ReactNode
}

/**
 * A chart in a panel: KPI tiles, a line, bars, a pie or a table, always with a data-table view.
 * Errors (including 503 "try again" answers) show the server's message with Retry.
 */
export function ChartPanel({ title, subtitle, visualization, data, error, loading, retry, errorHint, placeholder, stale }: ChartPanelProps) {
  const [view, setView] = useState<'chart' | 'table'>('chart')
  const hasChartView = visualization !== 'table'

  let body: ReactNode
  if (error) {
    body = (
      <>
        <ErrorState message={error.message} onRetry={retry} />
        {errorHint && <div className="chart-error-hint">{errorHint}</div>}
      </>
    )
  } else if (data === undefined) {
    body = loading ? <Skeleton height={visualization === 'kpi' ? 110 : 300} /> : placeholder
  } else {
    body = (
      <div className={loading ? 'is-refreshing' : undefined} aria-busy={loading}>
        {stale}
        <ChartBody result={data} visualization={visualization} view={hasChartView ? view : 'table'} />
      </div>
    )
  }

  return (
    <Panel
      title={title}
      subtitle={subtitle}
      className="chart-panel"
      actions={
        hasChartView && data !== undefined && !error ? (
          <Segmented
            label="View"
            options={[
              { value: 'chart', label: 'Chart' },
              { value: 'table', label: 'Table' },
            ]}
            value={view}
            onChange={setView}
          />
        ) : undefined
      }
    >
      {body}
    </Panel>
  )
}

/** The result drawn as `visualization`, or as its data table. */
export function ChartBody({ result, visualization, view }: { result: ChartResult; visualization: ChartVisualization; view: 'chart' | 'table' }) {
  if (visualization === 'kpi' && view === 'chart') return <KpiTiles result={result} />
  if (isEmpty(result)) return <EmptyState message="No sales match this chart’s dates and filters." />
  return (
    <>
      {view === 'table' || visualization === 'table' ? (
        <ChartTable result={result} />
      ) : visualization === 'line' ? (
        <LineView result={result} />
      ) : visualization === 'bar' ? (
        result.groupBy === 'time' ? (
          <TimeBarView result={result} />
        ) : (
          <GroupBarView result={result} />
        )
      ) : (
        <PieView result={result} />
      )}
      <ResultNotes result={result} />
    </>
  )
}

/** Truncation, overlapping orders and partial buckets: what the reader needs to read it right. */
function ResultNotes({ result }: { result: ChartResult }) {
  const notes: string[] = []
  const groupNoun = (GROUP_BY_LABELS[result.groupBy] ?? 'group').toLowerCase()
  const first = metricColumns(result)[0]
  if (result.truncated && result.totalGroups !== null) {
    notes.push(
      `Showing the top ${result.rows.length} of ${result.totalGroups} ${plural(groupNoun, result.totalGroups)} by ${first?.label.toLowerCase() ?? 'the first metric'}. Totals cover all of them.`,
    )
  }
  if ((result.groupBy === 'product' || result.groupBy === 'category') && metricColumns(result).some((m) => m.key === 'orders')) {
    notes.push(`An order with several ${plural(groupNoun, 2)} counts once in each, so orders by ${groupNoun} don’t add up to the total.`)
  }
  if (notes.length === 0) return null
  return (
    <ul className="chart-notes">
      {notes.map((note) => (
        <li key={note}>{note}</li>
      ))}
    </ul>
  )
}

function plural(noun: string, count: number): string {
  if (count === 1) return noun
  return noun.endsWith('y') ? `${noun.slice(0, -1)}ies` : `${noun}s`
}

// ---- KPI ---------------------------------------------------------------------------------------

function KpiTiles({ result }: { result: ChartResult }) {
  const metrics = metricColumns(result)
  const row = result.rows[0]
  return (
    <>
      <div className={`chart-kpis chart-kpis-${Math.min(metrics.length, 4)}`}>
        {metrics.map((m) => (
          <div key={m.key} className="metric-card chart-kpi">
            <span className="metric-label">{m.label}</span>
            <span className="metric-value">{formatMetric(row?.values[m.key] ?? result.totals[m.key] ?? 0, m.unit, result.currency)}</span>
            <span className="chart-kpi-caption">{formatDateRange(result.period.from, result.period.to)}</span>
          </div>
        ))}
      </div>
      {isEmpty(result) && <p className="chart-caption">No sales match this chart’s dates and filters.</p>}
    </>
  )
}

// ---- Line (time, one metric) -------------------------------------------------------------------

type SeriesRow = ChartRow & {
  value: number
  /** The value for complete buckets; null breaks the solid line around partial ones. */
  solid: number | null
  /** The value for partial buckets and their neighbours, drawn dashed. */
  dashed: number | null
}

function seriesRows(result: ChartResult, metric: ChartMetric): SeriesRow[] {
  const rows = result.rows
  return rows.map((row, i) => {
    const value = row.values[metric] ?? 0
    const touchesPartial = row.partial || rows[i - 1]?.partial === true || rows[i + 1]?.partial === true
    return { ...row, value, solid: row.partial ? null : value, dashed: touchesPartial ? value : null }
  })
}

function LineView({ result }: { result: ChartResult }) {
  const metric = metricColumns(result)[0]
  const granularity = result.granularity ?? 'day'
  const rows = seriesRows(result, metric.key)
  const hasPartial = rows.some((r) => r.partial)
  return (
    <div className="chart-frame">
      <ResponsiveContainer width="100%" height={300}>
        <ComposedChart data={rows} margin={{ top: 8, right: 12, bottom: 0, left: 0 }} accessibilityLayer>
          <CartesianGrid vertical={false} stroke={GRID} strokeWidth={1} />
          <XAxis
            dataKey="key"
            tickFormatter={(v: string) => formatBucketTick(v, granularity)}
            tick={{ fill: AXIS_TEXT, fontSize: 12 }}
            tickLine={false}
            axisLine={{ stroke: GRID }}
            minTickGap={24}
            tickMargin={8}
          />
          <ValueAxis unit={metric.unit} currency={result.currency} />
          <Tooltip
            cursor={{ stroke: AXIS_TEXT, strokeWidth: 1 }}
            content={({ active, payload }) =>
              active && payload?.length ? (
                <TimeTooltip row={payload[0].payload as SeriesRow} metric={metric} granularity={granularity} currency={result.currency} line />
              ) : null
            }
          />
          <Area type="monotone" dataKey="solid" name={metric.label} stroke="none" fill={SERIES} fillOpacity={0.1} isAnimationActive={false} />
          <Line
            type="monotone"
            dataKey="solid"
            name={metric.label}
            stroke={SERIES}
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
            dot={false}
            activeDot={{ r: 5, fill: SERIES, stroke: SURFACE, strokeWidth: 2 }}
            isAnimationActive={false}
          />
          {hasPartial && (
            <Line
              type="monotone"
              dataKey="dashed"
              name={`${metric.label} (partial period)`}
              stroke={SERIES}
              strokeWidth={2}
              strokeDasharray="5 5"
              dot={false}
              activeDot={{ r: 5, fill: SURFACE, stroke: SERIES, strokeWidth: 2 }}
              isAnimationActive={false}
              legendType="none"
            />
          )}
        </ComposedChart>
      </ResponsiveContainer>
      {hasPartial && (
        <p className="chart-caption">
          <span className="line-key-dashed" aria-hidden="true" /> Dashed segments mark {granularity}s only partly inside the dates.
        </p>
      )}
    </div>
  )
}

function TimeTooltip({
  row,
  metric,
  granularity,
  currency,
  line = false,
}: {
  row: ChartRow & { value: number }
  metric: { label: string; unit: MetricUnit }
  granularity: Granularity
  currency: string
  line?: boolean
}) {
  return (
    <div className="chart-tooltip">
      <p className="chart-tooltip-title">{formatBucketLabel(row.key, granularity)}</p>
      {row.partial && <p className="chart-tooltip-note">Partial {granularity}: only part of it is inside the dates</p>}
      <p className="chart-tooltip-row">
        <span className={line ? 'line-key' : 'swatch'} style={line ? { borderColor: SERIES } : { background: SERIES }} aria-hidden="true" />
        <span>{metric.label}</span>
        <strong>{formatMetric(row.value, metric.unit, currency)}</strong>
      </p>
    </div>
  )
}

function ValueAxis({ unit, currency, ...rest }: { unit: MetricUnit; currency: string; type?: 'number'; hide?: boolean }) {
  return (
    <YAxis
      tickFormatter={(v: number) => formatMetric(v, unit, currency, { compact: true })}
      tick={{ fill: AXIS_TEXT, fontSize: 12 }}
      tickLine={false}
      axisLine={false}
      width={64}
      allowDecimals={unit === 'money'}
      {...rest}
    />
  )
}

// ---- Bars --------------------------------------------------------------------------------------

/** Columns over time; partial buckets are drawn lighter (and said so). */
function TimeBarView({ result }: { result: ChartResult }) {
  const metric = metricColumns(result)[0]
  const granularity = result.granularity ?? 'day'
  const rows = result.rows.map((row) => ({ ...row, value: row.values[metric.key] ?? 0 }))
  const hasPartial = rows.some((r) => r.partial)
  return (
    <div className="chart-frame">
      <ResponsiveContainer width="100%" height={300}>
        <BarChart data={rows} margin={{ top: 8, right: 12, bottom: 0, left: 0 }} barCategoryGap={2} accessibilityLayer>
          <CartesianGrid vertical={false} stroke={GRID} strokeWidth={1} />
          <XAxis
            dataKey="key"
            tickFormatter={(v: string) => formatBucketTick(v, granularity)}
            tick={{ fill: AXIS_TEXT, fontSize: 12 }}
            tickLine={false}
            axisLine={{ stroke: GRID }}
            minTickGap={24}
            tickMargin={8}
          />
          <ValueAxis unit={metric.unit} currency={result.currency} />
          <Tooltip
            cursor={{ fill: 'rgb(42 120 214 / 0.06)' }}
            content={({ active, payload }) =>
              active && payload?.length ? (
                <TimeTooltip row={payload[0].payload as ChartRow & { value: number }} metric={metric} granularity={granularity} currency={result.currency} />
              ) : null
            }
          />
          <Bar dataKey="value" name={metric.label} maxBarSize={24} radius={[4, 4, 0, 0]} isAnimationActive={false}>
            {rows.map((row) => (
              <Cell key={row.key} fill={SERIES} fillOpacity={row.partial ? 0.4 : 1} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
      {hasPartial && (
        <p className="chart-caption">
          <span className="swatch" style={{ background: SERIES, opacity: 0.4 }} aria-hidden="true" /> Lighter bars are {granularity}s only
          partly inside the dates.
        </p>
      )}
    </div>
  )
}

const LABEL_CHARS = 18

function shortLabel(label: string): string {
  return label.length > LABEL_CHARS ? `${label.slice(0, LABEL_CHARS - 1)}…` : label
}

/** Horizontal bars for stores, products or categories (long names read left to right), value at the tip. */
function GroupBarView({ result }: { result: ChartResult }) {
  const metric = metricColumns(result)[0]
  const rows = result.rows.map((row) => ({ ...row, value: row.values[metric.key] ?? 0 }))
  const height = Math.max(120, rows.length * 34 + 24)
  // As wide as the longest (shortened) name needs, so short store names leave room for the bars.
  const axisWidth = Math.min(136, Math.max(56, Math.max(...rows.map((r) => shortLabel(r.label).length)) * 7 + 12))
  return (
    <div className="chart-frame chart-frame-auto">
      <ResponsiveContainer width="100%" height={height}>
        <BarChart data={rows} layout="vertical" margin={{ top: 4, right: 72, bottom: 4, left: 0 }} barCategoryGap={4} accessibilityLayer>
          <CartesianGrid horizontal={false} stroke={GRID} strokeWidth={1} />
          <XAxis type="number" hide />
          <YAxis
            type="category"
            dataKey="label"
            tickFormatter={shortLabel}
            tick={{ fill: AXIS_TEXT, fontSize: 12 }}
            tickLine={false}
            axisLine={{ stroke: GRID }}
            width={axisWidth}
            interval={0}
          />
          <Tooltip
            cursor={{ fill: 'rgb(42 120 214 / 0.06)' }}
            content={({ active, payload }) => {
              if (!active || !payload?.length) return null
              const row = payload[0].payload as ChartRow & { value: number }
              return (
                <div className="chart-tooltip">
                  <p className="chart-tooltip-title">{row.label}</p>
                  <p className="chart-tooltip-row">
                    <span className="swatch" style={{ background: SERIES }} aria-hidden="true" />
                    <span>{metric.label}</span>
                    <strong>{formatMetric(row.value, metric.unit, result.currency)}</strong>
                  </p>
                </div>
              )
            }}
          />
          <Bar dataKey="value" name={metric.label} fill={SERIES} maxBarSize={24} radius={[0, 4, 4, 0]} isAnimationActive={false}>
            <LabelList
              dataKey="value"
              position="right"
              formatter={(v: unknown) => formatMetric(Number(v), metric.unit, result.currency, { compact: true })}
              style={{ fill: 'var(--text-secondary)', fontSize: 12, fontVariantNumeric: 'tabular-nums' }}
            />
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

// ---- Pie (one additive metric) -----------------------------------------------------------------

/** Part-to-whole reads at a glance only with a few slices: the rest folds into "Other". */
const PIE_SLICES = CATEGORICAL.length

interface Slice {
  key: string
  label: string
  value: number
  color: string
}

function pieSlices(result: ChartResult, metric: ChartMetric): { slices: Slice[]; total: number } {
  const values = result.rows.map((row) => ({ key: row.key, label: row.label, value: row.values[metric] ?? 0 })).filter((s) => s.value > 0)
  const shown = values.length > PIE_SLICES ? values.slice(0, PIE_SLICES - 1) : values
  // Pies only take additive metrics, so the total minus the slices shown is everything else
  // (including groups beyond the limit).
  const total = Math.max(result.totals[metric] ?? 0, values.reduce((sum, s) => sum + s.value, 0))
  const slices: Slice[] = shown.map((s, i) => ({ ...s, color: CATEGORICAL[i] }))
  const rest = total - shown.reduce((sum, s) => sum + s.value, 0)
  if (rest > 0.004) slices.push({ key: '__other', label: 'Other', value: rest, color: OTHER_COLOR })
  return { slices, total }
}

function PieView({ result }: { result: ChartResult }) {
  const metric = metricColumns(result)[0]
  const { slices, total } = pieSlices(result, metric.key)
  const share = (value: number) => (total > 0 ? formatPercent((value / total) * 100) : '—')
  return (
    <div className="chart-pie">
      <div className="chart-pie-plot">
        <ResponsiveContainer width="100%" height={240}>
          <PieChart accessibilityLayer>
            <Pie
              data={slices}
              dataKey="value"
              nameKey="label"
              innerRadius="58%"
              outerRadius="92%"
              startAngle={90}
              endAngle={-270}
              stroke={SURFACE}
              strokeWidth={2}
              isAnimationActive={false}
            >
              {slices.map((s) => (
                <Cell key={s.key} fill={s.color} />
              ))}
            </Pie>
            <Tooltip
              content={({ active, payload }) => {
                if (!active || !payload?.length) return null
                const slice = payload[0].payload as Slice
                return (
                  <div className="chart-tooltip">
                    <p className="chart-tooltip-title">{slice.label}</p>
                    <p className="chart-tooltip-row">
                      <span className="swatch" style={{ background: slice.color }} aria-hidden="true" />
                      <span>{metric.label}</span>
                      <strong>{formatMetric(slice.value, metric.unit, result.currency)}</strong>
                    </p>
                    <p className="chart-tooltip-row">
                      <span className="swatch swatch-empty" aria-hidden="true" />
                      <span>Share</span>
                      <strong>{share(slice.value)}</strong>
                    </p>
                  </div>
                )
              }}
            />
          </PieChart>
        </ResponsiveContainer>
      </div>
      {/* The legend carries every value, so identity and amounts never depend on colour alone. */}
      <ul className="chart-legend" aria-label={`${metric.label} by ${(GROUP_BY_LABELS[result.groupBy] ?? '').toLowerCase()}`}>
        {slices.map((s) => (
          <li key={s.key} className="chart-legend-item">
            <span className="swatch" style={{ background: s.color }} aria-hidden="true" />
            <span className="chart-legend-label break-anywhere">{s.label}</span>
            <span className="chart-legend-value">{formatMetric(s.value, metric.unit, result.currency)}</span>
            <span className="chart-legend-share">{share(s.value)}</span>
          </li>
        ))}
        <li className="chart-legend-item chart-legend-total">
          <span className="swatch swatch-empty" aria-hidden="true" />
          <span className="chart-legend-label">Total</span>
          <span className="chart-legend-value">{formatMetric(total, metric.unit, result.currency)}</span>
          <span className="chart-legend-share">100.0%</span>
        </li>
      </ul>
    </div>
  )
}

// ---- Table (any chart, and every chart's table view) ------------------------------------------

export function ChartTable({ result }: { result: ChartResult }) {
  const metrics = metricColumns(result)
  const dimension = result.columns.find((c) => c.type === 'dimension')
  const kpi = result.groupBy === 'none'
  const dimensionLabel = dimension?.label ?? (result.groupBy === 'time' ? 'Period' : (GROUP_BY_LABELS[result.groupBy] ?? ''))
  return (
    <div className="table-scroll chart-table-scroll">
      <table className="data-table chart-table">
        <thead>
          <tr>
            <th scope="col">{kpi ? 'Period' : dimensionLabel}</th>
            {metrics.map((m) => (
              <th key={m.key} scope="col" className="num">
                {m.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {result.rows.map((row) => (
            <tr key={row.key}>
              <th scope="row" className="chart-table-label">
                <span className="break-anywhere">{kpi ? formatDateRange(result.period.from, result.period.to) : rowLabel(row, result)}</span>
                {row.partial && (
                  <span className="reports-partial chart-partial" title="Only part of this period is inside the dates">
                    Partial
                  </span>
                )}
              </th>
              {metrics.map((m) => (
                <td key={m.key} className="num">
                  {formatMetric(row.values[m.key], m.unit, result.currency)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
        {!kpi && (
          <tfoot>
            <tr>
              <th scope="row">{result.truncated ? 'Total (all groups)' : 'Total'}</th>
              {metrics.map((m) => (
                <td key={m.key} className="num">
                  {formatMetric(result.totals[m.key], m.unit, result.currency)}
                </td>
              ))}
            </tr>
          </tfoot>
        )}
      </table>
    </div>
  )
}
