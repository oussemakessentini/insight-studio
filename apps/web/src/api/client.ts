import type {
  DashboardContext,
  Granularity,
  ProblemDetail,
  ProductDetail,
  ProductListResponse,
  ProductSalesTrend,
  ProductSort,
  SaleDetail,
  SaleListResponse,
  SaleSort,
  SortDirection,
  RecentSalesResponse,
  RevenueSeries,
  StoreSalesResponse,
  Summary,
  TopProductsResponse,
} from './types'

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

/** Query parameters; null, undefined and empty strings are omitted. */
export type Params = Record<string, string | number | boolean | null | undefined>

const UNREACHABLE = 'Could not reach the API. Check that it is running on port 8080.'

function withQuery(path: string, params: Params): string {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== '') {
      query.set(key, String(value))
    }
  }
  return query.size > 0 ? `${path}?${query}` : path
}

/**
 * GET a JSON resource. Errors become an {@link ApiError} carrying the problem-detail message.
 * Feature API modules (api/<feature>.ts) build on this and {@link sendForm}.
 */
export function getJson<T>(path: string, params: Params = {}, signal?: AbortSignal): Promise<T> {
  return request<T>(withQuery(path, params), { signal, headers: { Accept: 'application/json' } })
}

/** POST multipart form data (e.g. a file upload) and read a JSON response. */
export function sendForm<T>(path: string, form: FormData, params: Params = {}, signal?: AbortSignal): Promise<T> {
  // No Content-Type header: the browser sets the multipart boundary itself.
  return request<T>(withQuery(path, params), { method: 'POST', body: form, signal, headers: { Accept: 'application/json' } })
}

async function request<T>(url: string, init: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(url, init)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, UNREACHABLE)
  }

  if (!response.ok) {
    let message = `Request failed (${response.status}).`
    try {
      const problem = (await response.json()) as ProblemDetail
      if (problem.detail) message = problem.detail
    } catch {
      // Not a problem-detail body (e.g. the dev proxy could not connect); keep the generic message.
      if (response.status === 502 || response.status === 504) {
        message = UNREACHABLE
      }
    }
    throw new ApiError(response.status, message)
  }
  return (await response.json()) as T
}

export interface DashboardFilter {
  from: string
  to: string
  storeId: number | null
}

const base = '/api/dashboard'

export const dashboardApi = {
  context: (signal?: AbortSignal) => getJson<DashboardContext>(`${base}/context`, {}, signal),

  summary: (f: DashboardFilter, signal?: AbortSignal) =>
    getJson<Summary>(`${base}/summary`, { ...f }, signal),

  revenue: (f: DashboardFilter, granularity: Granularity | null, signal?: AbortSignal) =>
    getJson<RevenueSeries>(`${base}/revenue`, { ...f, granularity }, signal),

  salesByStore: (f: DashboardFilter, signal?: AbortSignal) =>
    getJson<StoreSalesResponse>(`${base}/sales-by-store`, { ...f }, signal),

  topProducts: (f: DashboardFilter, limit: number, signal?: AbortSignal) =>
    getJson<TopProductsResponse>(`${base}/top-products`, { ...f, limit }, signal),

  recentSales: (f: DashboardFilter, limit: number, signal?: AbortSignal) =>
    getJson<RecentSalesResponse>(`${base}/recent-sales`, { ...f, limit }, signal),
}

export interface ProductListQuery {
  q: string
  category: string | null
  sort: ProductSort
  direction: SortDirection
  page: number
  size: number
}

export const productsApi = {
  list: (f: DashboardFilter, query: ProductListQuery, signal?: AbortSignal) =>
    getJson<ProductListResponse>('/api/products', { ...f, ...query }, signal),

  categories: (signal?: AbortSignal) =>
    getJson<{ categories: string[] }>('/api/products/categories', {}, signal),

  detail: (productId: number, f: DashboardFilter, signal?: AbortSignal) =>
    getJson<ProductDetail>(`/api/products/${productId}`, { ...f }, signal),

  salesTrend: (productId: number, f: DashboardFilter, granularity: Granularity | null, signal?: AbortSignal) =>
    getJson<ProductSalesTrend>(`/api/products/${productId}/sales-trend`, { ...f, granularity }, signal),
}

export interface SaleListQuery {
  q: string
  productId: number | null
  sort: SaleSort
  page: number
  size: number
}

export const salesApi = {
  list: (f: DashboardFilter, query: SaleListQuery, signal?: AbortSignal) =>
    getJson<SaleListResponse>('/api/sales', { ...f, ...query }, signal),

  detail: (saleId: number, signal?: AbortSignal) => getJson<SaleDetail>(`/api/sales/${saleId}`, {}, signal),
}
