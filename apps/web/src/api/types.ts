// Mirrors the DTOs in apps/api (com.oussamaksantini.insightstudio.dashboard.dto).
// Dates are ISO `yyyy-MM-dd` strings in the business time zone; instants are ISO UTC strings.

export interface DateRange {
  from: string
  to: string
}

export interface StoreOption {
  id: number
  code: string
  name: string
  city: string | null
}

export interface DashboardContext {
  business: {
    name: string
    slug: string
    currency: string
    timeZone: string
  }
  stores: StoreOption[]
  dataRange: DateRange | null
}

export interface MetricValue {
  value: number
  previousValue: number
  changePercent: number | null
}

export interface Summary {
  period: DateRange
  previousPeriod: DateRange
  storeId: number | null
  revenue: MetricValue
  orders: MetricValue
  unitsSold: MetricValue
  averageOrderValue: MetricValue
}

export type Granularity = 'day' | 'week' | 'month'

export interface RevenuePoint {
  periodStart: string
  revenue: number
  orders: number
  /** Days of this bucket inside the requested period. */
  daysCovered: number
  bucketDays: number
  /** False for an edge bucket that is only partly inside the period. */
  complete: boolean
}

export interface RevenueSeries {
  period: DateRange
  storeId: number | null
  granularity: Granularity
  points: RevenuePoint[]
}

export interface StoreSales {
  storeId: number
  code: string
  name: string
  city: string | null
  revenue: number
  orders: number
  unitsSold: number
  revenueSharePercent: number
}

export interface StoreSalesResponse {
  period: DateRange
  totalRevenue: number
  stores: StoreSales[]
}

export interface TopProduct {
  productId: number
  sku: string
  name: string
  category: string
  unitsSold: number
  revenue: number
  averageUnitPrice: number
}

export interface TopProductsResponse {
  period: DateRange
  storeId: number | null
  products: TopProduct[]
}

export interface RecentSale {
  saleId: number
  receiptNumber: string
  soldAt: string
  storeId: number
  storeName: string
  itemCount: number
  total: number
}

export interface RecentSalesResponse {
  period: DateRange
  storeId: number | null
  sales: RecentSale[]
}

/** RFC 9457 problem detail returned by the API on errors. */
export interface ProblemDetail {
  title?: string
  status?: number
  detail?: string
}

// ---- Products (apps/api product.dto) ----

export interface ProductInfo {
  id: number
  sku: string
  name: string
  category: string
  /** Current catalogue price; past sales may have been charged differently. */
  listPrice: number
}

export type ProductSort = 'revenue' | 'units' | 'name' | 'sku' | 'price'
export type SortDirection = 'asc' | 'desc'

export interface ProductListItem {
  productId: number
  sku: string
  name: string
  category: string
  listPrice: number
  unitsSold: number
  orders: number
  revenue: number
  /** Revenue / units at the prices charged; null when nothing sold in the period. */
  averageSellingPrice: number | null
}

export interface ProductListResponse {
  period: DateRange
  storeId: number | null
  query: string | null
  category: string | null
  sort: ProductSort
  direction: SortDirection
  page: number
  size: number
  totalItems: number
  totalPages: number
  items: ProductListItem[]
}

export interface PriceHistoryEntry {
  unitPrice: number
  firstSoldOn: string
  lastSoldOn: string
  unitsSold: number
  orders: number
}

/** `averageSellingPrice.value` is null when nothing sold in the period. */
export interface ProductDetail {
  product: ProductInfo
  period: DateRange
  previousPeriod: DateRange
  storeId: number | null
  revenue: MetricValue
  unitsSold: MetricValue
  orders: MetricValue
  averageSellingPrice: NullableMetricValue
  priceHistory: PriceHistoryEntry[]
}

export interface NullableMetricValue {
  value: number | null
  previousValue: number | null
  changePercent: number | null
}

export interface ProductTrendPoint extends RevenuePoint {
  unitsSold: number
  /** Null when nothing sold in the bucket. */
  averageUnitPrice: number | null
}

export interface ProductSalesTrend {
  productId: number
  period: DateRange
  storeId: number | null
  granularity: Granularity
  points: ProductTrendPoint[]
}
