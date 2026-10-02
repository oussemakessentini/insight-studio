import type { ReactNode } from 'react'
import type { ImportErrorItem, ImportField, ImportResult, ImportStatus } from '../../api/imports'
import { formatCurrency, formatNumber } from '../../lib/format'
import { StatTiles, type StatTile } from '../StatTiles'

const STATUS_LABELS: Record<ImportStatus, string> = {
  VALIDATED: 'Ready to import',
  IMPORTED: 'Imported',
  REJECTED: 'Rejected',
}

export function ImportStatusBadge({ status }: { status: ImportStatus }) {
  return <span className={`imports-status imports-status-${status.toLowerCase()}`}>{STATUS_LABELS[status]}</span>
}

/**
 * The figures for a validated, rejected or imported file: receipts and total for sales; created,
 * updated and unchanged records for stores and products.
 */
export function ImportCounts({ result, currency }: { result: ImportResult; currency: string }) {
  const rejected = result.status === 'REJECTED'
  const done = result.status === 'IMPORTED'
  const readable = rejected ? 'Among readable rows' : undefined
  const rows: StatTile = { label: 'Rows', value: formatNumber(result.rowCount), caption: 'Data rows, header excluded' }

  if (result.kind === 'sales') {
    return (
      <StatTiles
        tiles={[
          rows,
          {
            label: 'Receipts',
            value: formatNumber(result.saleCount),
            caption: rejected ? 'Among readable rows' : 'Store and receipt number pairs',
          },
          { label: 'Line items', value: formatNumber(result.lineCount), caption: readable },
          { label: 'Total', value: formatCurrency(result.totalAmount, currency), caption: 'Quantity × unit price' },
        ]}
      />
    )
  }

  const tiles: StatTile[] = [rows, { label: done ? 'Created' : 'To create', value: formatNumber(result.created), caption: readable }]
  if (result.mode === 'create_or_update') {
    tiles.push(
      { label: done ? 'Updated' : 'To update', value: formatNumber(result.updated), caption: readable },
      { label: 'Unchanged', value: formatNumber(result.unchanged), caption: readable ?? 'Identical to the file' },
    )
  }
  return <StatTiles tiles={tiles} />
}

/**
 * Updated products that move to another category take their past sales with them in category
 * reports (category is a current attribute); say so before and after importing.
 */
export function CategoryChangeNote({ count, done }: { count: number; done: boolean }) {
  if (count === 0) return null
  const one = count === 1
  return (
    <div className="imports-note" role="note">
      <strong>
        {one ? '1 updated product' : `${formatNumber(count)} updated products`}{' '}
        {done ? 'changed' : one ? 'changes' : 'change'} category.
      </strong>{' '}
      {one ? 'Its' : 'Their'} past sales {done ? 'now count' : 'will count'} under the new category in category reports.
      Revenue and the prices charged stay the same.
    </div>
  )
}

interface ImportErrorsTableProps {
  errors: ImportErrorItem[]
  errorCount: number
  /** The import's fields, to show labels instead of field names. */
  fields?: ImportField[]
  /** E.g. the errors CSV download, shown with the summary. */
  actions?: ReactNode
}

/** The reported errors (at most 100) with their line, target field and source column. */
export function ImportErrorsTable({ errors, errorCount, fields = [], actions }: ImportErrorsTableProps) {
  const labelOf = (field: string) => fields.find((f) => f.name === field)?.label ?? field
  const unlisted = errorCount - errors.length
  return (
    <div className="imports-errors">
      <div className="imports-errors-head">
        <p className="imports-errors-summary" role="status">
          {errorCount === 1 ? '1 problem found' : `${formatNumber(errorCount)} problems found`}
          {unlisted > 0 && ` · showing the first ${formatNumber(errors.length)}`}. Nothing was written.
        </p>
        {actions}
      </div>
      {errors.length > 0 && (
        <div className="table-scroll table-scroll-tall">
          <table className="data-table imports-errors-table">
            <thead>
              <tr>
                <th scope="col" className="num">Line</th>
                <th scope="col">Field</th>
                <th scope="col">Column in file</th>
                <th scope="col">Problem</th>
              </tr>
            </thead>
            <tbody>
              {errors.map((e, i) => (
                <tr key={i}>
                  <td className="num mono">{e.line ?? '–'}</td>
                  <td className="nowrap">{e.field ? labelOf(e.field) : <span className="text-muted">–</span>}</td>
                  <td className="mono imports-error-column">
                    {e.column ?? <span className="text-muted">{e.line === null ? 'file' : 'row'}</span>}
                  </td>
                  <td className="imports-error-message">{e.message}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {unlisted > 0 && (
        <p className="imports-hint">
          {unlisted === 1 ? '1 more error' : `${formatNumber(unlisted)} more errors`} not listed here; the errors CSV has
          every one.
        </p>
      )}
    </div>
  )
}
