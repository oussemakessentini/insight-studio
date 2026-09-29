import type { CategoryReport, CategoryReportRow } from '../../api/reports'
import { formatCurrency, formatNumber, formatPercent } from '../../lib/format'

export function CategoryReportTable({ report, currency }: { report: CategoryReport; currency: string }) {
  const { rows, totals } = report
  return (
    <>
      <div className="table-scroll">
        <table className="data-table reports-table">
          <caption className="reports-sr-only">Sales by product category</caption>
          <thead>
            <tr>
              <th scope="col">Category</th>
              <th scope="col" className="num">Revenue</th>
              <th scope="col" className="num">Share</th>
              <th scope="col" className="num hide-sm">Units</th>
              <th scope="col" className="num hide-md">Orders</th>
              <th scope="col" className="num hide-sm">Avg. unit price</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <CategoryRow key={row.category} row={row} currency={currency} />
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th scope="row">Total</th>
              <td className="num">{formatCurrency(totals.revenue, currency)}</td>
              <td className="num">{totals.revenue > 0 ? formatPercent(100) : '—'}</td>
              <td className="num hide-sm">{formatNumber(totals.unitsSold)}</td>
              <td className="num hide-md">{formatNumber(totals.orders)}</td>
              <td className="num hide-sm">
                {totals.unitsSold > 0 ? formatCurrency(totals.averageUnitPrice, currency) : '—'}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>
      <p className="reports-note">
        An order containing products from several categories counts once in each, so category orders can add up to
        more than the total.
      </p>
    </>
  )
}

function CategoryRow({ row, currency }: { row: CategoryReportRow; currency: string }) {
  const noSales = row.unitsSold === 0
  return (
    <tr className={noSales ? 'is-muted' : undefined}>
      <td className="reports-category-cell">
        <span className="cell-primary">{row.category}</span>
        <span className="reports-share-track" aria-hidden="true">
          <span className="reports-share-fill" style={{ width: `${Math.min(row.revenueSharePercent, 100)}%` }} />
        </span>
      </td>
      <td className="num strong">
        {noSales ? <span className="text-muted">No sales</span> : formatCurrency(row.revenue, currency)}
      </td>
      <td className="num">{formatPercent(row.revenueSharePercent)}</td>
      <td className="num hide-sm">{formatNumber(row.unitsSold)}</td>
      <td className="num hide-md">{formatNumber(row.orders)}</td>
      <td className="num hide-sm">
        {noSales ? <span className="text-muted">{'—'}</span> : formatCurrency(row.averageUnitPrice, currency)}
      </td>
    </tr>
  )
}
