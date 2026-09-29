import { ApiError, productsApi } from '../api/client'
import type { Granularity, PriceHistoryEntry, ProductDetail, ProductTrendPoint } from '../api/types'
import { FilterBar } from '../components/FilterBar'
import { Link } from '../components/Link'
import { MetricGrid, type MetricCardSpec } from '../components/MetricCards'
import { PageHeader } from '../components/PageHeader'
import { AsyncContent, ErrorState, Panel, Skeleton, SkeletonRows } from '../components/Panel'
import { PriceComparison } from '../components/PriceComparison'
import { TrendChart, type TrendDetail } from '../components/TrendChart'
import { useApi } from '../hooks/useApi'
import { useStateResetOn } from '../hooks/useStateResetOn'
import { formatCurrency, formatDateRange, formatNumber } from '../lib/format'
import { apiFilter, filterKey, type PageProps } from './types'

export function ProductDetailPage({ productId, context, filters, onFiltersChange, href }: PageProps & { productId: number }) {
  const { business, stores, dataRange } = context
  const query = apiFilter(filters)
  const key = `${productId}|${filterKey(filters)}`
  const [granularity, setGranularity] = useStateResetOn<Granularity | null>(key, null)

  const detail = useApi(`product|${key}`, (signal) => productsApi.detail(productId, query, signal))
  const trend = useApi(`product-trend|${key}|${granularity}`, (signal) =>
    productsApi.salesTrend(productId, query, granularity, signal),
  )

  if (detail.error instanceof ApiError && detail.error.status === 404) {
    return <ProductNotFound productsHref={href('/products')} />
  }

  const product = detail.data?.product
  const money = (v: number) => formatCurrency(v, business.currency)
  const selectedStore = stores.find((s) => s.id === filters.storeId)
  const cards: MetricCardSpec<ProductDetail>[] = [
    { label: 'Revenue', pick: (d) => d.revenue, format: money },
    { label: 'Units sold', pick: (d) => d.unitsSold, format: formatNumber },
    { label: 'Orders', pick: (d) => d.orders, format: formatNumber },
    {
      label: 'Average selling price',
      pick: (d) => d.averageSellingPrice,
      format: money,
      caption: (d) => `List price ${money(d.product.listPrice)}`,
    },
  ]
  const trendDetails: TrendDetail<ProductTrendPoint>[] = [
    { label: 'Units', value: (p) => formatNumber(p.unitsSold) },
    { label: 'Avg. price', value: (p) => (p.averageUnitPrice === null ? '—' : money(p.averageUnitPrice)) },
  ]

  return (
    <>
      <PageHeader
        eyebrow={
          <nav aria-label="Breadcrumb" className="breadcrumb">
            <Link href={href('/products')}>Products</Link>
            <span aria-hidden="true">/</span>
            <span>{product ? product.sku : '…'}</span>
          </nav>
        }
        title={product ? product.name : <Skeleton height={30} width={260} />}
        subtitle={
          product ? (
            <>
              <span className="chip">{product.category}</span> · List price {money(product.listPrice)}
              <span className="page-subtitle-muted">
                {' '}
                · {selectedStore ? selectedStore.name : 'All stores'} · {formatDateRange(filters.from, filters.to)}
              </span>
            </>
          ) : undefined
        }
      >
        <FilterBar filters={filters} stores={stores} dataRange={dataRange} onChange={onFiltersChange} />
      </PageHeader>

      <MetricGrid state={detail} cards={cards} previousPeriod={(d) => d.previousPeriod} />

      <div className="grid grid-main">
        <TrendChart
          title="Sales trend"
          subtitle="Revenue at the prices actually charged"
          state={trend}
          currency={business.currency}
          granularity={granularity}
          onGranularityChange={setGranularity}
          details={trendDetails}
          emptyMessage="This product had no sales in this period."
        />
        <Panel title="Prices charged" subtitle="Each price this product sold at in the period">
          <AsyncContent
            {...detail}
            isEmpty={(d) => d.priceHistory.length === 0}
            emptyMessage="No sales in this period."
            skeleton={<SkeletonRows rows={3} />}
          >
            {(d) => <PriceHistory entries={d.priceHistory} listPrice={d.product.listPrice} currency={business.currency} />}
          </AsyncContent>
        </Panel>
      </div>
    </>
  )
}

function PriceHistory({ entries, listPrice, currency }: { entries: PriceHistoryEntry[]; listPrice: number; currency: string }) {
  return (
    <ul className="price-history">
      {entries.map((e) => (
        <li key={`${e.unitPrice}-${e.firstSoldOn}`} className="price-history-item">
          <div className="price-history-head">
            <span className="price-history-price">{formatCurrency(e.unitPrice, currency)}</span>
            <PriceComparison price={e.unitPrice} listPrice={listPrice} />
          </div>
          <span className="price-history-dates">{formatDateRange(e.firstSoldOn, e.lastSoldOn)}</span>
          <span className="price-history-meta">
            {formatNumber(e.unitsSold)} {e.unitsSold === 1 ? 'unit' : 'units'} · {formatNumber(e.orders)}{' '}
            {e.orders === 1 ? 'order' : 'orders'}
          </span>
        </li>
      ))}
    </ul>
  )
}

function ProductNotFound({ productsHref }: { productsHref: string }) {
  return (
    <div className="panel page-error">
      <h1 className="page-title">Product not found</h1>
      <ErrorState message="This product does not exist or belongs to another business." />
      <p className="page-error-action">
        <Link className="button button-secondary" href={productsHref}>
          Back to products
        </Link>
      </p>
    </div>
  )
}
