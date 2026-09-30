import type { StoreListResponse, StorePerformance } from '../../api/stores'
import { formatCurrency, formatNumber, formatPercent } from '../../lib/format'
import { rowClick } from '../../lib/rowClick'
import { Link } from '../Link'
import { RevenueChange } from './RevenueChange'

interface StoreTableProps {
  data: StoreListResponse
  currency: string
  storeHref: (storeId: number) => string
}

/** Every store with its performance; the footer totals match the dashboard for the same dates. */
export function StoreTable({ data, currency, storeHref }: StoreTableProps) {
  const totals = data.stores.reduce(
    (t, s) => ({ revenue: t.revenue + s.revenue, orders: t.orders + s.orders, units: t.units + s.unitsSold }),
    { revenue: 0, orders: 0, units: 0 },
  )
  const maxRevenue = Math.max(0, ...data.stores.map((s) => s.revenue))
  return (
    <div className="table-scroll">
      <table className="data-table stores-table">
        <thead>
          <tr>
            <th scope="col">Store</th>
            <th scope="col" className="hide-sm">Share of revenue</th>
            <th scope="col" className="num">Revenue</th>
            <th scope="col" className="num">vs prev.</th>
            <th scope="col" className="num hide-sm">Orders</th>
            <th scope="col" className="num hide-md">Units</th>
            <th scope="col" className="num hide-md">Avg. order</th>
          </tr>
        </thead>
        <tbody>
          {data.stores.map((s) => (
            <StoreRow
              key={s.storeId}
              store={s}
              data={data}
              currency={currency}
              maxRevenue={maxRevenue}
              href={storeHref(s.storeId)}
            />
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row">All stores</th>
            <td className="hide-sm" />
            <td className="num">{formatCurrency(totals.revenue, currency)}</td>
            <td className="num" />
            <td className="num hide-sm">{formatNumber(totals.orders)}</td>
            <td className="num hide-md">{formatNumber(totals.units)}</td>
            <td className="num hide-md">
              {formatCurrency(totals.orders > 0 ? totals.revenue / totals.orders : 0, currency)}
            </td>
          </tr>
        </tfoot>
      </table>
    </div>
  )
}

interface StoreRowProps {
  store: StorePerformance
  data: StoreListResponse
  currency: string
  maxRevenue: number
  href: string
}

function StoreRow({ store: s, data, currency, maxRevenue, href }: StoreRowProps) {
  const noOrders = s.orders === 0
  const width = maxRevenue > 0 ? (s.revenue / maxRevenue) * 100 : 0
  return (
    <tr className={`is-clickable ${noOrders ? 'is-muted' : ''}`} onClick={rowClick(href)}>
      <td>
        <Link className="cell-primary cell-link" href={href}>
          {s.name}
        </Link>
        <span className="cell-secondary">
          {s.code} · {s.city ?? 'Online'}
          <span className="show-sm-inline"> · {formatPercent(s.revenueSharePercent)} of revenue</span>
        </span>
      </td>
      <td className="hide-sm">
        <div className="stores-share">
          <div className="stores-share-track" aria-hidden="true">
            {width > 0 && <div className="stores-share-fill" style={{ width: `${width}%` }} />}
          </div>
          <span className="stores-share-value">{formatPercent(s.revenueSharePercent)}</span>
        </div>
      </td>
      <td className="num strong">
        {noOrders ? <span className="text-muted">No sales</span> : formatCurrency(s.revenue, currency)}
      </td>
      <td className="num">
        {noOrders && s.revenueChangePercent === null ? (
          <span className="text-muted">{'—'}</span>
        ) : (
          <RevenueChange change={s.revenueChangePercent} previousPeriod={data.previousPeriod} />
        )}
      </td>
      <td className="num hide-sm">{formatNumber(s.orders)}</td>
      <td className="num hide-md">{formatNumber(s.unitsSold)}</td>
      <td className="num hide-md">{noOrders ? <span className="text-muted">{'—'}</span> : formatCurrency(s.averageOrderValue, currency)}</td>
    </tr>
  )
}
