import type { MonthlyReport, MonthlyReportRow } from '../../api/reports'
import { formatBucketLabel, formatCurrency, formatNumber } from '../../lib/format'
import { ChangeBadge } from './ChangeBadge'

export function MonthlyReportTable({ report, currency }: { report: MonthlyReport; currency: string }) {
  const { rows, totals } = report
  const hasPartial = rows.some((r) => !r.complete)
  return (
    <>
      <div className="table-scroll">
        <table className="data-table reports-table">
          <caption className="reports-sr-only">Sales by calendar month</caption>
          <thead>
            <tr>
              <th scope="col">Month</th>
              <th scope="col" className="num">Revenue</th>
              <th scope="col" className="num">vs prev.</th>
              <th scope="col" className="num">Orders</th>
              <th scope="col" className="num hide-sm">Units</th>
              <th scope="col" className="num hide-sm">Avg. order</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, i) => (
              <MonthRow key={row.month} row={row} previous={rows[i - 1]} currency={currency} />
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th scope="row">Total</th>
              <td className="num">{formatCurrency(totals.revenue, currency)}</td>
              <td className="num" aria-hidden="true" />
              <td className="num">{formatNumber(totals.orders)}</td>
              <td className="num hide-sm">{formatNumber(totals.unitsSold)}</td>
              <td className="num hide-sm">{formatCurrency(totals.averageOrderValue, currency)}</td>
            </tr>
          </tfoot>
        </table>
      </div>
      {hasPartial && (
        <p className="reports-note">
          <span className="reports-partial">Partial</span> months are only partly inside the selected dates, so they hold
          fewer days of sales; compare them with care.
        </p>
      )}
    </>
  )
}

function MonthRow({ row, previous, currency }: { row: MonthlyReportRow; previous?: MonthlyReportRow; currency: string }) {
  const noSales = row.orders === 0
  const partialNote = `${row.daysCovered} of ${row.daysInMonth} days selected`
  const changeTitle =
    row.revenueChangePercent !== null && previous && (!row.complete || !previous.complete)
      ? 'Compares a partial month; the months cover different numbers of days'
      : undefined
  return (
    <tr className={noSales ? 'is-muted' : undefined}>
      <td>
        <span className="cell-primary reports-month">
          <span className="nowrap">{formatBucketLabel(row.month, 'month')}</span>
          {!row.complete && <span className="reports-partial">Partial</span>}
        </span>
        {!row.complete && <span className="cell-secondary nowrap">{partialNote}</span>}
      </td>
      <td className="num strong">{formatCurrency(row.revenue, currency)}</td>
      <td className="num">
        <ChangeBadge value={row.revenueChangePercent} title={changeTitle} />
      </td>
      <td className="num">{formatNumber(row.orders)}</td>
      <td className="num hide-sm">{formatNumber(row.unitsSold)}</td>
      <td className="num hide-sm">{noSales ? <span className="text-muted">{'—'}</span> : formatCurrency(row.averageOrderValue, currency)}</td>
    </tr>
  )
}
