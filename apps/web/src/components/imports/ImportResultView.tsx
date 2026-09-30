import type { ImportErrorItem, ImportResult, ImportStatus } from '../../api/imports'
import { formatCurrency, formatNumber } from '../../lib/format'
import { StatTiles } from '../StatTiles'

const STATUS_LABELS: Record<ImportStatus, string> = {
  VALIDATED: 'Ready to import',
  IMPORTED: 'Imported',
  REJECTED: 'Rejected',
}

export function ImportStatusBadge({ status }: { status: ImportStatus }) {
  return <span className={`imports-status imports-status-${status.toLowerCase()}`}>{STATUS_LABELS[status]}</span>
}

/** Counts and total for a validated, rejected or imported file. */
export function ImportCounts({ result, currency }: { result: ImportResult; currency: string }) {
  const rejected = result.status === 'REJECTED'
  return (
    <StatTiles
      tiles={[
        { label: 'Rows', value: formatNumber(result.rowCount), caption: 'Data rows, header excluded' },
        {
          label: 'Receipts',
          value: formatNumber(result.saleCount),
          caption: rejected ? 'Among readable rows' : 'Store and receipt number pairs',
        },
        { label: 'Line items', value: formatNumber(result.lineCount), caption: rejected ? 'Among readable rows' : undefined },
        {
          label: 'Total',
          value: formatCurrency(result.totalAmount, currency),
          caption: 'Quantity × unit price',
        },
      ]}
    />
  )
}

/** The reported errors (at most 100) with their line and column. */
export function ImportErrorsTable({ errors, errorCount }: { errors: ImportErrorItem[]; errorCount: number }) {
  return (
    <div className="imports-errors">
      <p className="imports-errors-summary" role="status">
        {errorCount === 1 ? '1 problem found' : `${formatNumber(errorCount)} problems found`}
        {errorCount > errors.length && ` · showing the first ${formatNumber(errors.length)}`}. Nothing was written.
      </p>
      <div className="table-scroll table-scroll-tall">
        <table className="data-table imports-errors-table">
          <thead>
            <tr>
              <th scope="col" className="num">Line</th>
              <th scope="col">Column</th>
              <th scope="col">Problem</th>
            </tr>
          </thead>
          <tbody>
            {errors.map((e, i) => (
              <tr key={i}>
                <td className="num mono">{e.line ?? '–'}</td>
                <td className="mono">{e.column ?? <span className="text-muted">{e.line === null ? 'file' : 'row'}</span>}</td>
                <td className="imports-error-message">{e.message}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
