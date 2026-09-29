import type { TopProductsResponse } from '../api/types'
import type { ApiState } from '../hooks/useApi'
import { formatCurrency, formatNumber } from '../lib/format'
import { AsyncContent, Panel, SkeletonRows } from './Panel'

interface TopProductsProps {
  state: ApiState<TopProductsResponse>
  currency: string
}

export function TopProducts({ state, currency }: TopProductsProps) {
  return (
    <Panel title="Top products" subtitle="Ranked by revenue">
      <AsyncContent {...state} isEmpty={(d) => d.products.length === 0} skeleton={<SkeletonRows rows={5} />}>
        {(data) => (
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th scope="col" className="rank">#</th>
                  <th scope="col">Product</th>
                  <th scope="col" className="hide-sm">Category</th>
                  <th scope="col" className="num">Units</th>
                  <th scope="col" className="num hide-sm">Avg. price</th>
                  <th scope="col" className="num">Revenue</th>
                </tr>
              </thead>
              <tbody>
                {data.products.map((p, i) => (
                  <tr key={p.productId}>
                    <td className="rank">{i + 1}</td>
                    <td>
                      <span className="cell-primary">{p.name}</span>
                      <span className="cell-secondary">{p.sku}</span>
                    </td>
                    <td className="hide-sm">
                      <span className="chip">{p.category}</span>
                    </td>
                    <td className="num">{formatNumber(p.unitsSold)}</td>
                    <td className="num hide-sm">{formatCurrency(p.averageUnitPrice, currency)}</td>
                    <td className="num strong">{formatCurrency(p.revenue, currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </AsyncContent>
    </Panel>
  )
}
