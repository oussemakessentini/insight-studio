import { useId, useState } from 'react'
import { Area, CartesianGrid, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { Granularity, RevenuePoint } from '../api/types'
import type { ApiState } from '../hooks/useApi'
import { formatBucketLabel, formatBucketTick, formatCurrency, formatNumber } from '../lib/format'
import { AsyncContent, Panel, Skeleton } from './Panel'

// Chart tokens: one series -> categorical slot 1 (blue); recessive grid and axis ink.
const SERIES = '#2a78d6'
const GRID = '#e6e9ef'
const AXIS_TEXT = '#6b7688'
const SURFACE = '#ffffff'

const GRANULARITIES: { value: Granularity; label: string }[] = [
  { value: 'day', label: 'Day' },
  { value: 'week', label: 'Week' },
  { value: 'month', label: 'Month' },
]

export interface TrendSeries<P extends RevenuePoint> {
  granularity: Granularity
  points: P[]
}

/** Extra per-bucket values shown in the tooltip and as table columns, after revenue and orders. */
export interface TrendDetail<P> {
  label: string
  value: (point: P) => string
}

interface TrendChartProps<P extends RevenuePoint> {
  title: string
  subtitle: string
  state: ApiState<TrendSeries<P>>
  currency: string
  granularity: Granularity | null
  onGranularityChange: (g: Granularity) => void
  details?: TrendDetail<P>[]
  emptyMessage?: string
}

/** Revenue over time (single series) with a chart/table toggle and partial-bucket handling. */
export function TrendChart<P extends RevenuePoint>({
  title,
  subtitle,
  state,
  currency,
  granularity,
  onGranularityChange,
  details = [],
  emptyMessage,
}: TrendChartProps<P>) {
  const [view, setView] = useState<'chart' | 'table'>('chart')
  const active = granularity ?? state.data?.granularity ?? 'day'

  return (
    <Panel
      title={title}
      subtitle={subtitle}
      className="panel-revenue"
      actions={
        <>
          <Segmented label="Granularity" options={GRANULARITIES} value={active} onChange={onGranularityChange} />
          <Segmented
            label="View"
            options={[
              { value: 'chart', label: 'Chart' },
              { value: 'table', label: 'Table' },
            ]}
            value={view}
            onChange={setView}
          />
        </>
      }
    >
      <AsyncContent
        {...state}
        isEmpty={(d) => d.points.every((p) => p.orders === 0)}
        emptyMessage={emptyMessage}
        skeleton={<Skeleton height={280} />}
      >
        {(data) =>
          view === 'chart' ? (
            <Chart data={data} currency={currency} details={details} />
          ) : (
            <SeriesTable data={data} currency={currency} details={details} />
          )
        }
      </AsyncContent>
    </Panel>
  )
}

type ChartRow<P> = P & {
  /** Revenue for complete buckets; null breaks the solid area around partial ones. */
  solid: number | null
  /** Revenue for partial buckets and their neighbours, drawn dashed. */
  partial: number | null
}

/**
 * Edge buckets that are only partly inside the range (e.g. a week with one day selected) hold
 * fewer days of sales, so they are drawn dashed rather than as a misleading drop.
 */
function toChartRows<P extends RevenuePoint>(points: P[]): ChartRow<P>[] {
  return points.map((p, i) => {
    const touchesPartial = !p.complete || points[i - 1]?.complete === false || points[i + 1]?.complete === false
    return {
      ...p,
      solid: p.complete ? p.revenue : null,
      partial: touchesPartial ? p.revenue : null,
    }
  })
}

interface ViewProps<P extends RevenuePoint> {
  data: TrendSeries<P>
  currency: string
  details: TrendDetail<P>[]
}

function Chart<P extends RevenuePoint>({ data, currency, details }: ViewProps<P>) {
  const { granularity, points } = data
  const gradientId = `trend-fill-${useId().replace(/:/g, '')}`
  const rows = toChartRows(points)
  const hasPartial = points.some((p) => !p.complete)
  return (
    <div className="chart-frame">
      <ResponsiveContainer width="100%" height={300}>
        <ComposedChart data={rows} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
          <defs>
            <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={SERIES} stopOpacity={0.14} />
              <stop offset="100%" stopColor={SERIES} stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <CartesianGrid vertical={false} stroke={GRID} strokeWidth={1} />
          <XAxis
            dataKey="periodStart"
            tickFormatter={(v: string) => formatBucketTick(v, granularity)}
            tick={{ fill: AXIS_TEXT, fontSize: 12 }}
            tickLine={false}
            axisLine={{ stroke: GRID }}
            minTickGap={24}
            tickMargin={8}
          />
          <YAxis
            tickFormatter={(v: number) => formatCurrency(v, currency, { compact: true })}
            tick={{ fill: AXIS_TEXT, fontSize: 12 }}
            tickLine={false}
            axisLine={false}
            width={64}
            allowDecimals={false}
          />
          <Tooltip
            cursor={{ stroke: AXIS_TEXT, strokeWidth: 1 }}
            content={({ active, payload }) => {
              if (!active || !payload?.length) return null
              const point = payload[0].payload as ChartRow<P>
              return (
                <div className="chart-tooltip">
                  <p className="chart-tooltip-title">{formatBucketLabel(point.periodStart, granularity)}</p>
                  {!point.complete && (
                    <p className="chart-tooltip-note">
                      Partial {granularity}: {point.daysCovered} of {point.bucketDays} days in range
                    </p>
                  )}
                  <p className="chart-tooltip-row">
                    <span className="swatch" style={{ background: SERIES }} aria-hidden="true" />
                    <span>Revenue</span>
                    <strong>{formatCurrency(point.revenue, currency)}</strong>
                  </p>
                  <p className="chart-tooltip-row">
                    <span className="swatch swatch-empty" aria-hidden="true" />
                    <span>Orders</span>
                    <strong>{formatNumber(point.orders)}</strong>
                  </p>
                  {details.map((d) => (
                    <p key={d.label} className="chart-tooltip-row">
                      <span className="swatch swatch-empty" aria-hidden="true" />
                      <span>{d.label}</span>
                      <strong>{d.value(point)}</strong>
                    </p>
                  ))}
                </div>
              )
            }}
          />
          <Area
            type="monotone"
            dataKey="solid"
            name="Revenue"
            stroke={SERIES}
            strokeWidth={2}
            fill={`url(#${gradientId})`}
            dot={false}
            activeDot={{ r: 5, fill: SERIES, stroke: SURFACE, strokeWidth: 2 }}
            isAnimationActive={false}
          />
          {hasPartial && (
            <Line
              type="monotone"
              dataKey="partial"
              name="Revenue (partial period)"
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
          <span className="line-key-dashed" aria-hidden="true" /> Dashed segments mark {granularity}s only partly inside
          the selected range.
        </p>
      )}
    </div>
  )
}

function SeriesTable<P extends RevenuePoint>({ data, currency, details }: ViewProps<P>) {
  return (
    <div className="table-scroll table-scroll-tall">
      <table className="data-table">
        <thead>
          <tr>
            <th scope="col">Period</th>
            <th scope="col" className="num">Orders</th>
            {details.map((d) => (
              <th key={d.label} scope="col" className="num">
                {d.label}
              </th>
            ))}
            <th scope="col" className="num">Revenue</th>
          </tr>
        </thead>
        <tbody>
          {data.points.map((p) => (
            <tr key={p.periodStart}>
              <td>
                {formatBucketLabel(p.periodStart, data.granularity)}
                {!p.complete && (
                  <span className="cell-secondary">
                    Partial: {p.daysCovered} of {p.bucketDays} days
                  </span>
                )}
              </td>
              <td className="num">{formatNumber(p.orders)}</td>
              {details.map((d) => (
                <td key={d.label} className="num">
                  {d.value(p)}
                </td>
              ))}
              <td className="num">{formatCurrency(p.revenue, currency)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

interface SegmentedProps<T extends string> {
  label: string
  options: { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}

export function Segmented<T extends string>({ label, options, value, onChange }: SegmentedProps<T>) {
  return (
    <div className="segmented" role="group" aria-label={label}>
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          className={o.value === value ? 'is-selected' : undefined}
          aria-pressed={o.value === value}
          onClick={() => onChange(o.value)}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}
