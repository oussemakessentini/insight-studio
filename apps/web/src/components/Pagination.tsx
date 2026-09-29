import { formatNumber } from '../lib/format'
import { EmptyState } from './Panel'

export interface PageInfo {
  /** Zero-based. */
  page: number
  size: number
  totalItems: number
  totalPages: number
}

interface PaginationProps {
  info: PageInfo
  /** Rows on the current page. */
  count: number
  onPage: (page: number) => void
}

export function Pagination({ info, count, onPage }: PaginationProps) {
  const first = info.page * info.size + 1
  const last = first + count - 1
  return (
    <nav className="pagination" aria-label="Pagination">
      <p className="pagination-summary">
        Showing {formatNumber(first)}–{formatNumber(last)} of {formatNumber(info.totalItems)}
      </p>
      {info.totalPages > 1 && (
        <div className="pagination-controls">
          <button
            type="button"
            className="button button-secondary"
            disabled={info.page === 0}
            onClick={() => onPage(info.page - 1)}
          >
            Previous
          </button>
          <span className="pagination-page">
            Page {formatNumber(info.page + 1)} of {formatNumber(info.totalPages)}
          </span>
          <button
            type="button"
            className="button button-secondary"
            disabled={info.page >= info.totalPages - 1}
            onClick={() => onPage(info.page + 1)}
          >
            Next
          </button>
        </div>
      )}
    </nav>
  )
}

/** A page number past the end, e.g. from an old bookmark after the results shrank. */
export function PastLastPage({ info, onPage }: { info: PageInfo; onPage: (page: number) => void }) {
  return (
    <div className="empty-with-action">
      <EmptyState
        message={`Page ${formatNumber(info.page + 1)} is past the end of the results (${formatNumber(info.totalPages)} ${info.totalPages === 1 ? 'page' : 'pages'}).`}
      />
      <button type="button" className="button button-secondary" onClick={() => onPage(info.totalPages - 1)}>
        Go to the last page
      </button>
    </div>
  )
}
