import { useEffect, useState } from 'react'
import { ApiError, salesApi } from '../api/client'
import type { SaleListResponse, SaleSort } from '../api/types'
import { FilterBar } from '../components/FilterBar'
import { CloseIcon } from '../components/Icons'
import { Link } from '../components/Link'
import { PageHeader } from '../components/PageHeader'
import { Pagination, PastLastPage } from '../components/Pagination'
import { AsyncContent, EmptyState, Panel, SkeletonRows } from '../components/Panel'
import { useApi } from '../hooks/useApi'
import { useDebounced } from '../hooks/useDebounced'
import { useStateResetOn } from '../hooks/useStateResetOn'
import { formatCurrency, formatDateRange, formatDateTime, formatNumber } from '../lib/format'
import { updateQuery } from '../lib/router'
import { rowClick } from '../lib/rowClick'
import { apiFilter, filterKey, type PageProps } from './types'

const PAGE_SIZE = 25
const SEARCH_DEBOUNCE_MS = 300

const SORT_OPTIONS: { value: SaleSort; label: string }[] = [
  { value: 'newest', label: 'Newest first' },
  { value: 'oldest', label: 'Oldest first' },
  { value: 'largest', label: 'Largest total' },
]
const DEFAULT_SORT: SaleSort = 'newest'

/** Page-specific state read once from the URL, so links and back/forward restore the view. */
function initialStateFromUrl() {
  const params = new URLSearchParams(window.location.search)
  const sort = params.get('sort') as SaleSort | null
  const page = Number(params.get('page'))
  const product = Number(params.get('product'))
  return {
    q: params.get('q') ?? '',
    productId: Number.isInteger(product) && product > 0 ? product : null,
    sort: SORT_OPTIONS.some((o) => o.value === sort) ? sort! : DEFAULT_SORT,
    page: Number.isInteger(page) && page > 1 ? page - 1 : 0,
  }
}

export function SalesPage({ context, filters, onFiltersChange, href }: PageProps) {
  const { business, stores, dataRange } = context
  const [initial] = useState(initialStateFromUrl)
  const [searchInput, setSearchInput] = useState(initial.q)
  const search = useDebounced(searchInput, SEARCH_DEBOUNCE_MS).trim()
  const [productId, setProductId] = useState(initial.productId)
  const [sort, setSort] = useState<SaleSort>(initial.sort)

  // Any change to what is being listed starts again from the first page.
  const listKey = `${filterKey(filters)}|${search}|${productId}|${sort}`
  const [page, setPage] = useStateResetOn(listKey, 0, initial.page)

  useEffect(() => {
    updateQuery({
      q: search || null,
      product: productId !== null ? String(productId) : null,
      sort: sort !== DEFAULT_SORT ? sort : null,
      page: page > 0 ? String(page + 1) : null,
    })
  }, [search, productId, sort, page])

  const list = useApi(`sale-list|${listKey}|${page}`, (signal) =>
    salesApi.list(apiFilter(filters), { q: search, productId, sort, page, size: PAGE_SIZE }, signal),
  )

  const selectedStore = stores.find((s) => s.id === filters.storeId)
  // Only trust the name from a response for the product currently in the filter (not stale data).
  const product = list.data?.product?.id === productId ? list.data!.product : null
  const productNotFound = productId !== null && list.error instanceof ApiError && list.error.status === 404
  const hasCriteria = Boolean(search || productId)
  const clearCriteria = () => {
    setSearchInput('')
    setProductId(null)
  }

  return (
    <>
      <PageHeader
        eyebrow={business.name}
        title="Sales"
        subtitle={
          <>
            {selectedStore ? selectedStore.name : 'All stores'} · {formatDateRange(filters.from, filters.to)}
            <span className="page-subtitle-muted"> · Times in {business.timeZone.replaceAll('_', ' ')}</span>
          </>
        }
      >
        <FilterBar filters={filters} stores={stores} dataRange={dataRange} onChange={onFiltersChange} />
      </PageHeader>

      <Panel
        title="Receipts"
        subtitle={
          list.data
            ? `${formatNumber(list.data.totalItems)} ${list.data.totalItems === 1 ? 'receipt' : 'receipts'}`
            : undefined
        }
      >
        <div className="toolbar" role="search">
          <label className="field toolbar-search">
            <span className="field-label">Receipt number</span>
            <input
              type="search"
              className="control"
              placeholder="e.g. BOS-20260831"
              value={searchInput}
              maxLength={40}
              onChange={(e) => setSearchInput(e.target.value)}
            />
          </label>
          <label className="field">
            <span className="field-label">Sort by</span>
            <select className="control" value={sort} onChange={(e) => setSort(e.target.value as SaleSort)}>
              {SORT_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        </div>

        {productId !== null && (
          <p className="filter-chips">
            <span className="filter-chip">
              Containing{' '}
              {product ? (
                <Link href={href(`/products/${product.id}`)}>
                  {product.name} <span className="text-muted">({product.sku})</span>
                </Link>
              ) : (
                `product ${productId}`
              )}
              <button type="button" className="filter-chip-remove" onClick={() => setProductId(null)} aria-label="Remove product filter">
                <CloseIcon width={14} height={14} />
              </button>
            </span>
          </p>
        )}

        {productNotFound ? (
          // A stale or edited ?product= link: retrying can't help, removing the filter can.
          <div className="empty-with-action">
            <EmptyState message={`${list.error!.message} The product filter can't be applied.`} />
            <button type="button" className="button button-secondary" onClick={() => setProductId(null)}>
              Remove product filter
            </button>
          </div>
        ) : (
          <AsyncContent {...list} skeleton={<SkeletonRows rows={10} />}>
            {(data) =>
              data.totalItems === 0 ? (
                <NoSales search={search} productName={product?.name} onClear={hasCriteria ? clearCriteria : undefined} />
              ) : data.items.length === 0 ? (
                <PastLastPage info={data} onPage={setPage} />
              ) : (
                <>
                  <SalesTable
                    data={data}
                    currency={business.currency}
                    timeZone={business.timeZone}
                    saleHref={(id) => href(`/sales/${id}`)}
                  />
                  <Pagination info={data} count={data.items.length} onPage={setPage} />
                </>
              )
            }
          </AsyncContent>
        )}
      </Panel>
    </>
  )
}

function SalesTable({
  data,
  currency,
  timeZone,
  saleHref,
}: {
  data: SaleListResponse
  currency: string
  timeZone: string
  saleHref: (id: number) => string
}) {
  return (
    <div className="table-scroll">
      <table className="data-table">
        <thead>
          <tr>
            <th scope="col">Receipt</th>
            <th scope="col" className="hide-sm">Date</th>
            <th scope="col" className="hide-sm">Store</th>
            <th scope="col" className="num hide-sm" title="Distinct products on the receipt">Items</th>
            <th scope="col" className="num">Units</th>
            <th scope="col" className="num">Total</th>
          </tr>
        </thead>
        <tbody>
          {data.items.map((s) => (
            <tr key={s.saleId} className="is-clickable" onClick={rowClick(saleHref(s.saleId))}>
              <td>
                <Link className="cell-primary cell-link mono" href={saleHref(s.saleId)}>
                  {s.receiptNumber}
                </Link>
                {/* On phones the date and store move under the receipt number. */}
                <span className="cell-secondary show-sm">
                  {formatDateTime(s.soldAt, timeZone)} · {s.storeName}
                </span>
              </td>
              <td className="nowrap hide-sm">{formatDateTime(s.soldAt, timeZone)}</td>
              <td className="hide-sm">{s.storeName}</td>
              <td className="num hide-sm">{formatNumber(s.lineCount)}</td>
              <td className="num">{formatNumber(s.unitCount)}</td>
              <td className="num strong">{formatCurrency(s.total, currency)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function NoSales({ search, productName, onClear }: { search: string; productName?: string; onClear?: () => void }) {
  const criteria = [search && `receipt “${search}”`, productName && `containing ${productName}`]
    .filter(Boolean)
    .join(' ')
  return (
    <div className="empty-with-action">
      <EmptyState message={criteria ? `No sales match ${criteria} in this period.` : 'No sales in this period.'} />
      {onClear && (
        <button type="button" className="button button-secondary" onClick={onClear}>
          Clear search and product filter
        </button>
      )}
    </div>
  )
}
