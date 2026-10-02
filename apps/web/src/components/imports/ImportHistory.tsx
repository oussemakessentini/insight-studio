import { IMPORT_KINDS, importsApi, type ImportBatchSummary, type ImportKind, type ImportListResponse } from '../../api/imports'
import { useApi } from '../../hooks/useApi'
import { formatCurrency, formatDateTime, formatNumber } from '../../lib/format'
import { rowClick } from '../../lib/rowClick'
import { Link } from '../Link'
import { Pagination, PastLastPage } from '../Pagination'
import { AsyncContent, EmptyState, Panel, SkeletonRows } from '../Panel'
import { ImportStatusBadge } from './ImportResultView'
import { countOf, KINDS } from './kinds'

export const HISTORY_PAGE_SIZE = 20

interface ImportHistoryProps {
  page: number
  onPage: (page: number) => void
  /** Only imports of this type; null for all. */
  kind: ImportKind | null
  onKind: (kind: ImportKind | null) => void
  /** Changes whenever a new import may have been added, to reload the list. */
  refreshKey: number
  currency: string
  timeZone: string
  batchHref: (batchId: number) => string
}

/** Imported and rejected files of the current business, newest first. */
export function ImportHistory({ page, onPage, kind, onKind, refreshKey, currency, timeZone, batchHref }: ImportHistoryProps) {
  const list = useApi(`import-list|${kind ?? 'all'}|${page}|${refreshKey}`, (signal) =>
    importsApi.list(page, HISTORY_PAGE_SIZE, kind, signal),
  )

  return (
    <Panel
      title="Import history"
      subtitle={
        list.data
          ? `${formatNumber(list.data.totalItems)} ${list.data.totalItems === 1 ? 'file' : 'files'} · newest first`
          : 'Newest first'
      }
      actions={
        <label className="field imports-history-filter">
          <span className="field-label">Type</span>
          <select
            className="control"
            value={kind ?? ''}
            onChange={(e) => onKind(IMPORT_KINDS.find((k) => k === e.target.value) ?? null)}
          >
            <option value="">All types</option>
            {IMPORT_KINDS.map((k) => (
              <option key={k} value={k}>
                {KINDS[k].label}
              </option>
            ))}
          </select>
        </label>
      }
    >
      <AsyncContent {...list} skeleton={<SkeletonRows rows={4} />}>
        {(data) =>
          data.totalItems === 0 ? (
            <EmptyState
              message={
                kind
                  ? `No ${KINDS[kind].label.toLowerCase()} files imported yet.`
                  : 'No files imported yet. Imports you run, and rejected attempts, appear here.'
              }
            />
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
      <table className="data-table imports-history-table">
        <thead>
          <tr>
            <th scope="col">File</th>
            <th scope="col" className="hide-sm">Type</th>
            <th scope="col">Outcome</th>
            <th scope="col" className="hide-sm">Result</th>
            <th scope="col" className="hide-md">Imported by</th>
            <th scope="col" className="hide-sm">When</th>
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
                  <span className="show-sm-inline">
                    {' '}
                    · {KINDS[b.kind].label} · {formatDateTime(b.createdAt, timeZone)}
                  </span>
                </span>
                <span className="cell-secondary show-sm">{batchCounts(b, currency)}</span>
              </td>
              <td className="hide-sm">{KINDS[b.kind].label}</td>
              <td>
                <ImportStatusBadge status={b.status} />
              </td>
              <td className="hide-sm imports-history-counts">{batchCounts(b, currency)}</td>
              <td className="hide-md">{b.importedBy ?? <span className="text-muted">–</span>}</td>
              <td className="nowrap hide-sm">{formatDateTime(b.createdAt, timeZone)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** What a batch did, in the terms of its type: "8 receipts · $860.10", "12 created · 3 updated". */
function batchCounts(b: ImportBatchSummary, currency: string): string {
  if (b.status === 'REJECTED') {
    return `${b.errorCount === 1 ? '1 error' : `${formatNumber(b.errorCount)} errors`} · nothing written`
  }
  if (b.kind === 'sales') return `${countOf('sales', b.saleCount, formatNumber)} · ${formatCurrency(b.totalAmount, currency)}`
  const parts = [`${formatNumber(b.created)} created`]
  if (b.mode === 'create_or_update') parts.push(`${formatNumber(b.updated)} updated`, `${formatNumber(b.unchanged)} unchanged`)
  return parts.join(' · ')
}
