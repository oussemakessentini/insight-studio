import type { StoreSalesResponse } from '../api/types'
import type { ApiState } from '../hooks/useApi'
import { formatCurrency, formatNumber, formatPercent } from '../lib/format'
import { AsyncContent, Panel, SkeletonRows } from './Panel'

interface StoreSalesProps {
  state: ApiState<StoreSalesResponse>
  currency: string
}

/** Horizontal bars of revenue per store (one measure, one hue), labelled at the tip. */
export function StoreSales({ state, currency }: StoreSalesProps) {
  return (
    <Panel title="Sales by store" subtitle="Share of revenue in the selected period" className="panel-stores">
      <AsyncContent {...state} isEmpty={(d) => d.stores.length === 0} emptyMessage="No stores yet." skeleton={<SkeletonRows rows={4} />}>
        {(data) => {
          const max = Math.max(...data.stores.map((s) => s.revenue), 0)
          return (
            <ul className="store-bars">
              {data.stores.map((store) => {
                const width = max > 0 ? (store.revenue / max) * 100 : 0
                const detail = `${store.name}: ${formatCurrency(store.revenue, currency)} · ${formatNumber(store.orders)} orders · ${formatNumber(store.unitsSold)} units`
                return (
                  <li key={store.storeId} className="store-bar" title={detail}>
                    <div className="store-bar-head">
                      <span className="store-bar-name">
                        {store.name}
                        <span className="store-bar-city">{store.city ?? 'Online'}</span>
                      </span>
                      <span className="store-bar-share">{formatPercent(store.revenueSharePercent)}</span>
                    </div>
                    <div className="store-bar-row">
                      <div className="store-bar-track">
                        {width > 0 && <div className="store-bar-fill" style={{ width: `${width}%` }} />}
                      </div>
                      <span className="store-bar-value">{formatCurrency(store.revenue, currency, { compact: true })}</span>
                    </div>
                    <span className="store-bar-meta">{formatNumber(store.orders)} orders</span>
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
