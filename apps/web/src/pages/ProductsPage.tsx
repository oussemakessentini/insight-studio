import { useEffect, useState } from 'react'
import { productsApi } from '../api/client'
import type { ProductListItem, ProductListResponse, ProductSort, SortDirection } from '../api/types'
import { FilterBar } from '../components/FilterBar'
import { Link } from '../components/Link'
import { PageHeader } from '../components/PageHeader'
import { Pagination, PastLastPage } from '../components/Pagination'
import { AsyncContent, EmptyState, Panel, SkeletonRows } from '../components/Panel'
import { PriceComparison } from '../components/PriceComparison'
import { useApi } from '../hooks/useApi'
import { useDebounced } from '../hooks/useDebounced'
import { useStateResetOn } from '../hooks/useStateResetOn'
import { formatCurrency, formatDateRange, formatNumber } from '../lib/format'
import { updateQuery } from '../lib/router'
import { rowClick } from '../lib/rowClick'
import { apiFilter, filterKey, type PageProps } from './types'

const PAGE_SIZE = 20
const SEARCH_DEBOUNCE_MS = 300

const SORT_OPTIONS: { value: `${ProductSort}:${SortDirection}`; label: string }[] = [
  { value: 'revenue:desc', label: 'Revenue: high to low' },
  { value: 'revenue:asc', label: 'Revenue: low to high' },
  { value: 'units:desc', label: 'Units sold: high to low' },
  { value: 'name:asc', label: 'Name: A to Z' },
  { value: 'price:desc', label: 'Price: high to low' },
  { value: 'price:asc', label: 'Price: low to high' },
]
const DEFAULT_SORT = SORT_OPTIONS[0].value
type SortOption = (typeof SORT_OPTIONS)[number]['value']

/** Page-specific state read once from the URL, so links and back/forward restore the view. */
function initialStateFromUrl() {
  const params = new URLSearchParams(window.location.search)
  const sort = params.get('sort') as SortOption | null
  const page = Number(params.get('page'))
  return {
    q: params.get('q') ?? '',
    category: params.get('category') ?? '',
    sort: SORT_OPTIONS.some((o) => o.value === sort) ? sort! : DEFAULT_SORT,
    page: Number.isInteger(page) && page > 1 ? page - 1 : 0,
  }
}

export function ProductsPage({ context, filters, onFiltersChange, href }: PageProps) {
  const { business, stores, dataRange } = context
  const [initial] = useState(initialStateFromUrl)
  const [searchInput, setSearchInput] = useState(initial.q)
  const search = useDebounced(searchInput, SEARCH_DEBOUNCE_MS).trim()
  const [category, setCategory] = useState(initial.category)
  const [sort, setSort] = useState<SortOption>(initial.sort)

  // Any change to what is being listed starts again from the first page.
  const listKey = `${filterKey(filters)}|${search}|${category}|${sort}`
  const [page, setPage] = useStateResetOn(listKey, 0, initial.page)

  useEffect(() => {
    updateQuery({
      q: search || null,
      category: category || null,
      sort: sort !== DEFAULT_SORT ? sort : null,
      page: page > 0 ? String(page + 1) : null,
    })
  }, [search, category, sort, page])

  const [sortField, direction] = sort.split(':') as [ProductSort, SortDirection]
  const categories = useApi('product-categories', (signal) => productsApi.categories(signal))
  const list = useApi(`product-list|${listKey}|${page}`, (signal) =>
    productsApi.list(
      apiFilter(filters),
      { q: search, category: category || null, sort: sortField, direction, page, size: PAGE_SIZE },
      signal,
    ),
  )

  const selectedStore = stores.find((s) => s.id === filters.storeId)
  const hasCriteria = Boolean(search || category)
  const clearCriteria = () => {
    setSearchInput('')
    setCategory('')
  }

  return (
    <>
      <PageHeader
        eyebrow={business.name}
        title="Products"
        subtitle={
          <>
            {selectedStore ? selectedStore.name : 'All stores'} · {formatDateRange(filters.from, filters.to)}
            <span className="page-subtitle-muted"> · Sales at the prices charged</span>
          </>
        }
      >
        <FilterBar filters={filters} stores={stores} dataRange={dataRange} onChange={onFiltersChange} />
      </PageHeader>

      <Panel
        title="Catalogue"
        subtitle={list.data ? `${formatNumber(list.data.totalItems)} ${list.data.totalItems === 1 ? 'product' : 'products'}` : undefined}
      >
        <div className="toolbar" role="search">
          <label className="field toolbar-search">
            <span className="field-label">Search</span>
            <input
              type="search"
              className="control"
              placeholder="Name or SKU"
              value={searchInput}
              maxLength={100}
              onChange={(e) => setSearchInput(e.target.value)}
            />
          </label>
          <label className="field">
            <span className="field-label">Category</span>
            <select className="control" value={category} onChange={(e) => setCategory(e.target.value)}>
              <option value="">All categories</option>
              {categories.data?.categories.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">Sort by</span>
            <select className="control" value={sort} onChange={(e) => setSort(e.target.value as SortOption)}>
              {SORT_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        </div>

        <AsyncContent {...list} skeleton={<SkeletonRows rows={8} />}>
          {(data) =>
            data.totalItems === 0 ? (
              <NoResults search={search} category={category} onClear={hasCriteria ? clearCriteria : undefined} />
            ) : data.items.length === 0 ? (
              <PastLastPage info={data} onPage={setPage} />
            ) : (
              <>
                <ProductTable data={data} currency={business.currency} productHref={(id) => href(`/products/${id}`)} />
                <Pagination info={data} count={data.items.length} onPage={setPage} />
              </>
            )
          }
        </AsyncContent>
      </Panel>
    </>
  )
}

function ProductTable({
  data,
  currency,
  productHref,
}: {
  data: ProductListResponse
  currency: string
  productHref: (id: number) => string
}) {
  return (
    <div className="table-scroll">
      <table className="data-table">
        <thead>
          <tr>
            <th scope="col">Product</th>
            <th scope="col" className="hide-sm">Category</th>
            <th scope="col" className="num">Price</th>
            <th scope="col" className="num hide-sm">Avg. sold at</th>
            <th scope="col" className="num">Units</th>
            <th scope="col" className="num hide-md">Orders</th>
            <th scope="col" className="num">Revenue</th>
          </tr>
        </thead>
        <tbody>
          {data.items.map((p) => (
            <ProductRow key={p.productId} product={p} currency={currency} href={productHref(p.productId)} />
          ))}
        </tbody>
      </table>
    </div>
  )
}

function ProductRow({ product: p, currency, href }: { product: ProductListItem; currency: string; href: string }) {
  const noSales = p.unitsSold === 0
  return (
    <tr className={`is-clickable ${noSales ? 'is-muted' : ''}`} onClick={rowClick(href)}>
      <td>
        <Link className="cell-primary cell-link" href={href}>
          {p.name}
        </Link>
        <span className="cell-secondary">
          {p.sku}
          <span className="show-sm-inline"> · {p.category}</span>
        </span>
      </td>
      <td className="hide-sm">
        <span className="chip">{p.category}</span>
      </td>
      <td className="num">{formatCurrency(p.listPrice, currency)}</td>
      <td className="num hide-sm">
        {p.averageSellingPrice === null ? (
          <span className="text-muted">{'—'}</span>
        ) : (
          <>
            {formatCurrency(p.averageSellingPrice, currency)}
            <PriceComparison price={p.averageSellingPrice} listPrice={p.listPrice} />
          </>
        )}
      </td>
      <td className="num">{formatNumber(p.unitsSold)}</td>
      <td className="num hide-md">{formatNumber(p.orders)}</td>
      <td className="num strong">{noSales ? <span className="text-muted">No sales</span> : formatCurrency(p.revenue, currency)}</td>
    </tr>
  )
}

function NoResults({ search, category, onClear }: { search: string; category: string; onClear?: () => void }) {
  const criteria = [search && `“${search}”`, category && `in ${category}`].filter(Boolean).join(' ')
  return (
    <div className="empty-with-action">
      <EmptyState message={criteria ? `No products match ${criteria}.` : 'There are no products in the catalogue yet.'} />
      {onClear && (
        <button type="button" className="button button-secondary" onClick={onClear}>
          Clear search and category
        </button>
      )}
    </div>
  )
}
