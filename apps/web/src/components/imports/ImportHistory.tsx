import { importsApi, type ImportListResponse } from '../../api/imports'
import { useApi } from '../../hooks/useApi'
import { formatCurrency, formatDateTime, formatNumber } from '../../lib/format'
import { rowClick } from '../../lib/rowClick'
import { Link } from '../Link'
import { Pagination, PastLastPage } from '../Pagination'
import { AsyncContent, EmptyState, Panel, SkeletonRows } from '../Panel'

export const HISTORY_PAGE_SIZE = 20

interface ImportHistoryProps {
  page: number
  onPage: (page: number) => void
  /** Changes whenever a new import may have been added, to reload the list. */
  refreshKey: number
  currency: string
  timeZone: string
  batchHref: (batchId: number) => string
}

/** Successful imports of the current business, newest first. */
export function ImportHistory({ page, onPage, refreshKey, currency, timeZone, batchHref }: ImportHistoryProps) {
  const list = useApi(`import-list|${page}|${refreshKey}`, (signal) => importsApi.list(page, HISTORY_PAGE_SIZE, null, signal))

  return (
    <Panel
      title="Import history"
      subtitle={
        list.data
          ? `${formatNumber(list.data.totalItems)} ${list.data.totalItems === 1 ? 'import' : 'imports'} · newest first`
          : 'Newest first'
      }
    >
      <AsyncContent {...list} skeleton={<SkeletonRows rows={4} />}>
        {(data) =>
          data.totalItems === 0 ? (
            <EmptyState message="No files imported yet. Imports you complete appear here." />
          ) : data.items.length === 0 ? (
            <PastLastPage info={data} onPage={onPage} />
          ) : (
            <>
              <HistoryTable data={data} currency={currency} timeZone={timeZone} batchHref={batchHref} />
              <Pagination info={data} count={data.items.length} onPage={onPage} />
            </>
          )
        }
      </AsyncContent>
    </Panel>
  )
}

function HistoryTable({
  data,
  currency,
  timeZone,
  batchHref,
}: {
  data: ImportListResponse
  currency: string
  timeZone: string
  batchHref: (batchId: number) => string
}) {
  return (
    <div className="table-scroll">
      <table className="data-table">
        <thead>
          <tr>
            <th scope="col">File</th>
            <th scope="col" className="hide-sm">Imported</th>
            <th scope="col" className="num">Receipts</th>
            <th scope="col" className="num hide-sm">Line items</th>
            <th scope="col" className="num">Total</th>
          </tr>
        </thead>
        <tbody>
          {data.items.map((b) => (
            <tr key={b.batchId} className="is-clickable" onClick={rowClick(batchHref(b.batchId))}>
              <td className="imports-file-cell">
                <Link className="cell-primary cell-link imports-file-name" href={batchHref(b.batchId)}>
                  {b.fileName}
                </Link>
                <span className="cell-secondary">
                  Import #{b.batchId}
                  <span className="show-sm-inline"> · {formatDateTime(b.createdAt, timeZone)}</span>
                </span>
              </td>
              <td className="nowrap hide-sm">{formatDateTime(b.createdAt, timeZone)}</td>
              <td className="num">{formatNumber(b.saleCount)}</td>
              <td className="num hide-sm">{formatNumber(b.lineCount)}</td>
              <td className="num strong">{formatCurrency(b.totalAmount, currency)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
