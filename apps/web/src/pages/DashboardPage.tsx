import { dashboardApi } from '../api/client'
import type { Granularity, Summary } from '../api/types'
import { FilterBar } from '../components/FilterBar'
import { MetricGrid, type MetricCardSpec } from '../components/MetricCards'
import { PageHeader } from '../components/PageHeader'
import { RecentSales } from '../components/RecentSales'
import { StoreSales } from '../components/StoreSales'
import { TopProducts } from '../components/TopProducts'
import { TrendChart } from '../components/TrendChart'
import { useApi } from '../hooks/useApi'
import { useStateResetOn } from '../hooks/useStateResetOn'
import { formatCurrency, formatDate, formatDateRange, formatNumber } from '../lib/format'
import { apiFilter, filterKey, type PageProps } from './types'

const TOP_PRODUCTS_LIMIT = 8
const RECENT_SALES_LIMIT = 8

export function DashboardPage({ context, filters, onFiltersChange, href }: PageProps) {
  const { business, stores, dataRange } = context
  const query = apiFilter(filters)
  const key = filterKey(filters)
  // null = let the API pick a bucket size that suits the range; reset when filters change.
  const [granularity, setGranularity] = useStateResetOn<Granularity | null>(key, null)

  const summary = useApi(`summary|${key}`, (signal) => dashboardApi.summary(query, signal))
  const revenue = useApi(`revenue|${key}|${granularity}`, (signal) => dashboardApi.revenue(query, granularity, signal))
  const byStore = useApi(`stores|${key}`, (signal) => dashboardApi.salesByStore(query, signal))
  const products = useApi(`products|${key}`, (signal) => dashboardApi.topProducts(query, TOP_PRODUCTS_LIMIT, signal))
  const recent = useApi(`recent|${key}`, (signal) => dashboardApi.recentSales(query, RECENT_SALES_LIMIT, signal))

  const selectedStore = stores.find((s) => s.id === filters.storeId)
  const money = (v: number) => formatCurrency(v, business.currency)
  const cards: MetricCardSpec<Summary>[] = [
    { label: 'Revenue', pick: (s) => s.revenue, format: money },
    { label: 'Orders', pick: (s) => s.orders, format: formatNumber },
    { label: 'Average order value', pick: (s) => s.averageOrderValue, format: money },
    { label: 'Units sold', pick: (s) => s.unitsSold, format: formatNumber },
  ]

  return (
    <>
      <PageHeader
        eyebrow={business.name}
        title="Sales overview"
        subtitle={
          <>
            {selectedStore ? selectedStore.name : 'All stores'} · {formatDateRange(filters.from, filters.to)}
            {dataRange && <span className="page-subtitle-muted"> · Data through {formatDate(dataRange.to)}</span>}
          </>
        }
      >
        <FilterBar filters={filters} stores={stores} dataRange={dataRange} onChange={onFiltersChange} />
      </PageHeader>

      <MetricGrid state={summary} cards={cards} previousPeriod={(s) => s.previousPeriod} />

      <div className="grid grid-main">
        <TrendChart
          title="Revenue over time"
          subtitle="Revenue from items sold, at the prices charged"
          state={revenue}
          currency={business.currency}
          granularity={granularity}
          onGranularityChange={setGranularity}
        />
        <StoreSales state={byStore} currency={business.currency} />
      </div>

      <div className="grid grid-halves">
        <TopProducts
          state={products}
          currency={business.currency}
          productHref={(id) => href(`/products/${id}`)}
          allProductsHref={href('/products')}
        />
        <RecentSales state={recent} currency={business.currency} timeZone={business.timeZone} />
      </div>
    </>
  )
}
