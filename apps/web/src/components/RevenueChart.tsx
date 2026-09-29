import { useState } from 'react'
import { Area, CartesianGrid, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { Granularity, RevenuePoint, RevenueSeries } from '../api/types'
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

interface RevenueChartProps {
  state: ApiState<RevenueSeries>
  currency: string
  granularity: Granularity | null
  onGranularityChange: (g: Granularity) => void
}

export function RevenueChart({ state, currency, granularity, onGranularityChange }: RevenueChartProps) {
  const [view, setView] = useState<'chart' | 'table'>('chart')
  const active = granularity ?? state.data?.granularity ?? 'day'

  return (
    <Panel
      title="Revenue over time"
      subtitle="Revenue from items sold, at the prices charged"
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
        skeleton={<Skeleton height={280} />}
      >
        {(data) => (view === 'chart' ? <Chart data={data} currency={currency} /> : <SeriesTable data={data} currency={currency} />)}
      </AsyncContent>
    </Panel>
  )
}

interface ChartRow extends RevenuePoint {
  /** Revenue for complete buckets; null breaks the solid area around partial ones. */
  solid: number | null
  /** Revenue for partial buckets and their neighbours, drawn dashed. */
  partial: number | null
}

/**
 * Edge buckets that are only partly inside the range (e.g. a week with one day selected) hold
 * fewer days of sales, so they are drawn dashed rather than as a misleading drop.
 */
function toChartRows(points: RevenuePoint[]): ChartRow[] {
  return points.map((p, i) => {
    const touchesPartial = !p.complete || points[i - 1]?.complete === false || points[i + 1]?.complete === false
    return {
      ...p,
      solid: p.complete ? p.revenue : null,
      partial: touchesPartial ? p.revenue : null,
    }
  })
}

function Chart({ data, currency }: { data: RevenueSeries; currency: string }) {
  const { granularity, points } = data
  const rows = toChartRows(points)
  const hasPartial = points.some((p) => !p.complete)
  return (
    <div className="chart-frame">
      <ResponsiveContainer width="100%" height={300}>
        <ComposedChart data={rows} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
          <defs>
            <linearGradient id="revenueFill" x1="0" y1="0" x2="0" y2="1">
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
              const point = payload[0].payload as ChartRow
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
            fill="url(#revenueFill)"
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

function SeriesTable({ data, currency }: { data: RevenueSeries; currency: string }) {
  return (
    <div className="table-scroll table-scroll-tall">
      <table className="data-table">
        <thead>
          <tr>
            <th scope="col">Period</th>
            <th scope="col" className="num">Orders</th>
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

function Segmented<T extends string>({ label, options, value, onChange }: SegmentedProps<T>) {
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
