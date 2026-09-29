import { useEffect, useState } from 'react'
import { dashboardApi, type DashboardFilter } from '../api/client'
import type { DashboardContext, Granularity } from '../api/types'
import { FilterBar } from '../components/FilterBar'
import { MetricCards } from '../components/MetricCards'
import { RecentSales } from '../components/RecentSales'
import { RevenueChart } from '../components/RevenueChart'
import { StoreSales } from '../components/StoreSales'
import { TopProducts } from '../components/TopProducts'
import { useApi } from '../hooks/useApi'
import { filtersFromUrl, writeFiltersToUrl, type Filters } from '../lib/filters'
import { formatDate, formatDateRange } from '../lib/format'

const TOP_PRODUCTS_LIMIT = 8
const RECENT_SALES_LIMIT = 8

export function DashboardPage({ context }: { context: DashboardContext }) {
  const { business, stores, dataRange } = context
  const [filters, setFilters] = useState<Filters>(() =>
    filtersFromUrl(dataRange, stores.map((s) => s.id)),
  )
  // null = let the API pick a bucket size that suits the range.
  const [granularity, setGranularity] = useState<Granularity | null>(null)

  useEffect(() => writeFiltersToUrl(filters), [filters])

  const query: DashboardFilter = { from: filters.from, to: filters.to, storeId: filters.storeId }
  const key = `${query.from}|${query.to}|${query.storeId ?? 'all'}`

  const summary = useApi(`summary|${key}`, (signal) => dashboardApi.summary(query, signal))
  const revenue = useApi(`revenue|${key}|${granularity}`, (signal) => dashboardApi.revenue(query, granularity, signal))
  const byStore = useApi(`stores|${key}`, (signal) => dashboardApi.salesByStore(query, signal))
  const products = useApi(`products|${key}`, (signal) => dashboardApi.topProducts(query, TOP_PRODUCTS_LIMIT, signal))
  const recent = useApi(`recent|${key}`, (signal) => dashboardApi.recentSales(query, RECENT_SALES_LIMIT, signal))

  const selectedStore = stores.find((s) => s.id === filters.storeId)

  return (
    <>
      <header className="page-header">
        <div>
          <p className="page-eyebrow">{business.name}</p>
          <h1 className="page-title">Sales overview</h1>
          <p className="page-subtitle">
            {selectedStore ? selectedStore.name : 'All stores'} · {formatDateRange(filters.from, filters.to)}
            {dataRange && <span className="page-subtitle-muted"> · Data through {formatDate(dataRange.to)}</span>}
          </p>
        </div>
        <FilterBar
          filters={filters}
          stores={stores}
          dataRange={dataRange}
          onChange={(next) => {
            setFilters(next)
            setGranularity(null)
          }}
        />
      </header>

      <MetricCards state={summary} currency={business.currency} />

      <div className="grid grid-main">
        <RevenueChart
          state={revenue}
          currency={business.currency}
          granularity={granularity}
          onGranularityChange={setGranularity}
        />
        <StoreSales state={byStore} currency={business.currency} />
      </div>

      <div className="grid grid-halves">
        <TopProducts state={products} currency={business.currency} />
        <RecentSales state={recent} currency={business.currency} timeZone={business.timeZone} />
      </div>
    </>
  )
}
