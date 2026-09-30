import type { StoreDetail } from '../../api/stores'
import type { ApiState } from '../../hooks/useApi'
import { formatCurrency, formatNumber, formatPercent } from '../../lib/format'
import { AsyncContent, Panel, SkeletonRows } from '../Panel'

interface CategoryMixProps {
  state: ApiState<StoreDetail>
  currency: string
}

/** Share of the store's revenue per product category: one measure, one hue, labelled at the tip. */
export function CategoryMix({ state, currency }: CategoryMixProps) {
  return (
    <Panel title="Category mix" subtitle="Share of this store's revenue" className="stores-category-panel">
      <AsyncContent {...state} isEmpty={(d) => d.categories.length === 0} skeleton={<SkeletonRows rows={4} />}>
        {(data) => {
          const max = Math.max(0, ...data.categories.map((c) => c.revenue))
          return (
            <ul className="store-bars">
              {data.categories.map((c) => {
                const width = max > 0 ? (c.revenue / max) * 100 : 0
                return (
                  <li key={c.category} className="store-bar">
                    <div className="store-bar-head">
                      <span className="store-bar-name">{c.category}</span>
                      <span className="store-bar-share">{formatPercent(c.revenueSharePercent)}</span>
                    </div>
                    <div className="store-bar-row">
                      <div className="store-bar-track" aria-hidden="true">
                        {width > 0 && <div className="store-bar-fill" style={{ width: `${width}%` }} />}
                      </div>
                      <span className="store-bar-value">{formatCurrency(c.revenue, currency, { compact: true })}</span>
                    </div>
                    <span className="store-bar-meta">
                      {formatNumber(c.unitsSold)} {c.unitsSold === 1 ? 'unit' : 'units'} · {formatNumber(c.orders)}{' '}
                      {c.orders === 1 ? 'order' : 'orders'}
                    </span>
                  </li>
                )
              })}
            </ul>
          )
        }}
      </AsyncContent>
    </Panel>
  )
}
