import { ApiError } from '../api/client'
import { storesApi, type StoreDetail } from '../api/stores'
import type { Granularity } from '../api/types'
import { FilterBar } from '../components/FilterBar'
import { Link } from '../components/Link'
import { MetricGrid, type MetricCardSpec } from '../components/MetricCards'
import { PageHeader } from '../components/PageHeader'
import { ErrorState, Skeleton } from '../components/Panel'
import { CategoryMix } from '../components/stores/CategoryMix'
import { TopProducts } from '../components/TopProducts'
import { TrendChart } from '../components/TrendChart'
import { useApi } from '../hooks/useApi'
import { useStateResetOn } from '../hooks/useStateResetOn'
import { formatCurrency, formatDateRange, formatNumber } from '../lib/format'
import '../styles/stores.css'
import type { PageProps } from './types'

const TOP_PRODUCTS_LIMIT = 8

/** One store's performance. The store comes from the path, so the global store filter is ignored. */
export function StoreDetailPage({ storeId, context, filters, onFiltersChange, href }: PageProps & { storeId: number }) {
  const { business, stores, dataRange } = context
  const dates = { from: filters.from, to: filters.to }
  const key = `${storeId}|${filters.from}|${filters.to}`
  // null = let the API pick a bucket size that suits the range; reset when the dates change.
  const [granularity, setGranularity] = useStateResetOn<Granularity | null>(key, null)

  const detail = useApi(`store|${key}`, (signal) => storesApi.detail(storeId, dates, signal))
  const revenue = useApi(`store-revenue|${key}|${granularity}`, (signal) =>
    storesApi.revenue(storeId, dates, granularity, signal),
  )
  const products = useApi(`store-products|${key}`, (signal) =>
    storesApi.topProducts(storeId, dates, TOP_PRODUCTS_LIMIT, signal),
  )

  if (detail.error instanceof ApiError && detail.error.status === 404) {
    return <StoreNotFound storesHref={href('/stores')} />
  }

  const store = detail.data?.store
  const money = (v: number) => formatCurrency(v, business.currency)
  const cards: MetricCardSpec<StoreDetail>[] = [
    { label: 'Revenue', pick: (d) => d.revenue, format: money },
    { label: 'Orders', pick: (d) => d.orders, format: formatNumber },
    { label: 'Average order value', pick: (d) => d.averageOrderValue, format: money },
    { label: 'Units sold', pick: (d) => d.unitsSold, format: formatNumber },
  ]

  return (
    <>
      <PageHeader
        eyebrow={
          <nav aria-label="Breadcrumb" className="breadcrumb">
            <Link href={href('/stores')}>Stores</Link>
            <span aria-hidden="true">/</span>
            <span>{store ? store.code : '…'}</span>
          </nav>
        }
        title={store ? store.name : <Skeleton height={30} width={240} />}
        subtitle={
          store ? (
            <>
              {store.city ?? 'Online'}
              <span className="page-subtitle-muted"> · {formatDateRange(filters.from, filters.to)}</span>
            </>
          ) : undefined
        }
      >
        <FilterBar filters={filters} stores={stores} dataRange={dataRange} onChange={onFiltersChange} showStore={false} />
      </PageHeader>

      <MetricGrid state={detail} cards={cards} previousPeriod={(d) => d.previousPeriod} />

      <div className="grid grid-main">
        <TrendChart
          title="Revenue over time"
          subtitle="This store's revenue, at the prices charged"
          state={revenue}
          currency={business.currency}
          granularity={granularity}
          onGranularityChange={setGranularity}
          emptyMessage="This store had no sales in this period."
        />
        <CategoryMix state={detail} currency={business.currency} />
      </div>

      <TopProducts
        state={products}
        currency={business.currency}
        productHref={(id) => href(`/products/${id}`)}
        allProductsHref={href('/products')}
      />
    </>
  )
}

function StoreNotFound({ storesHref }: { storesHref: string }) {
  return (
    <div className="panel page-error">
      <h1 className="page-title">Store not found</h1>
      <ErrorState message="This store does not exist or belongs to another business." />
      <p className="page-error-action">
        <Link className="button button-secondary" href={storesHref}>
          Back to stores
        </Link>
      </p>
    </div>
  )
}
