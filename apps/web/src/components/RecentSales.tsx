import type { RecentSalesResponse } from '../api/types'
import type { ApiState } from '../hooks/useApi'
import { formatCurrency, formatDateTime, formatNumber } from '../lib/format'
import { rowClick } from '../lib/rowClick'
import { Link } from './Link'
import { AsyncContent, Panel, SkeletonRows } from './Panel'

interface RecentSalesProps {
  state: ApiState<RecentSalesResponse>
  currency: string
  timeZone: string
  saleHref: (saleId: number) => string
  allSalesHref: string
}

export function RecentSales({ state, currency, timeZone, saleHref, allSalesHref }: RecentSalesProps) {
  return (
    <Panel
      title="Recent sales"
      subtitle={`Latest in the period · times in ${timeZone.replaceAll('_', ' ')}`}
      actions={
        <Link className="panel-link" href={allSalesHref}>
          View all sales
        </Link>
      }
    >
      <AsyncContent {...state} isEmpty={(d) => d.sales.length === 0} skeleton={<SkeletonRows rows={6} />}>
        {(data) => (
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th scope="col">Receipt</th>
                  <th scope="col">Date</th>
                  <th scope="col" className="hide-sm">Store</th>
                  <th scope="col" className="num hide-sm">Units</th>
                  <th scope="col" className="num">Total</th>
                </tr>
              </thead>
              <tbody>
                {data.sales.map((s) => (
                  <tr key={s.saleId} className="is-clickable" onClick={rowClick(saleHref(s.saleId))}>
                    <td>
                      <Link className="cell-primary cell-link mono" href={saleHref(s.saleId)}>
                        {s.receiptNumber}
                      </Link>
                      <span className="cell-secondary show-sm">{s.storeName}</span>
                    </td>
                    <td className="nowrap">{formatDateTime(s.soldAt, timeZone)}</td>
                    <td className="hide-sm">{s.storeName}</td>
                    {/* itemCount is the total quantity (units) on the receipt. */}
                    <td className="num hide-sm">{formatNumber(s.itemCount)}</td>
                    <td className="num strong">{formatCurrency(s.total, currency)}</td>
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
