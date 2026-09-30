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
  /** Seconds to wait before retrying, from a `Retry-After` header (429). */
  readonly retryAfterSeconds: number | null

  constructor(status: number, message: string, retryAfterSeconds: number | null = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.retryAfterSeconds = retryAfterSeconds
  }
}

/** Query parameters; null, undefined and empty strings are omitted. */
export type Params = Record<string, string | number | boolean | null | undefined>

/**
 * Where API requests end up: the dev server's proxy target (API_PROXY_TARGET, set in vite.config)
 * or, in a production build, this page's own /api address.
 */
export const API_LOCATION: string = __API_PROXY_TARGET__ ?? `${window.location.origin}/api`

const UNREACHABLE = `Could not reach the API at ${API_LOCATION}. Check that it is running.`
const FORBIDDEN = "You don't have permission to do this."

/** Dispatched on `window` when a request that needs a session answers 401 (the session ended). */
export const UNAUTHORIZED_EVENT = 'app:unauthorized'

// ---- Business selection (contract §3) ----------------------------------------------------------

// The business the signed-in user works on, sent as `X-Business-Id` on business-scoped calls. Null
// for the public demo (the server resolves it) and before a business is chosen. The server treats
// the id only as a selector among the user's own memberships; it is not a security boundary.
let selectedBusinessId: number | null = null

/** Sets the business sent with business-scoped requests (null: send none). */
export function setBusinessScope(businessId: number | null): void {
  selectedBusinessId = businessId
}

// ---- CSRF (contract §1) ------------------------------------------------------------------------

const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'

function readCookie(name: string): string | null {
  for (const part of document.cookie.split(';')) {
    const [key, ...rest] = part.trim().split('=')
    if (key === name) return decodeURIComponent(rest.join('='))
  }
  return null
}

/** The CSRF token; when the cookie is missing, GET /api/session (which always issues it) first. */
async function csrfToken(signal?: AbortSignal): Promise<string | null> {
  const existing = readCookie(CSRF_COOKIE)
  if (existing) return existing
  try {
    await fetch('/api/session', { headers: { Accept: 'application/json' }, signal, credentials: 'same-origin' })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    // Unreachable: the request itself will report it.
  }
  return readCookie(CSRF_COOKIE)
}

function withQuery(path: string, params: Params): string {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== '') {
      query.set(key, String(value))
    }
  }
  return query.size > 0 ? `${path}?${query}` : path
}

export interface RequestOptions {
  signal?: AbortSignal
  /**
   * The business the call is about. Defaults to the selected one (see setBusinessScope); `null`
   * sends no `X-Business-Id` (session and account endpoints). Calls with a business id in the path
   * pass that id, because the server checks path ids against the resolved business.
   */
  businessId?: number | null
  /**
   * A 401 is an expected answer here (e.g. a wrong password on sign-in), not an ended session:
   * don't send the user to the sign-in page.
   */
  expectUnauthorized?: boolean
}

/**
 * GET a JSON resource. Errors become an {@link ApiError} carrying the problem-detail message.
 * Feature API modules (api/<feature>.ts) build on this, {@link sendForm} and the JSON writers.
 */
export function getJson<T>(path: string, params: Params = {}, signal?: AbortSignal, options: RequestOptions = {}): Promise<T> {
  return request<T>(withQuery(path, params), 'GET', undefined, { ...options, signal })
}

/** POST multipart form data (e.g. a file upload) and read a JSON response. */
export function sendForm<T>(path: string, form: FormData, params: Params = {}, signal?: AbortSignal): Promise<T> {
  // No Content-Type header: the browser sets the multipart boundary itself.
  return request<T>(withQuery(path, params), 'POST', form, { signal })
}

/** POST a JSON body. Resolves to the parsed response, or undefined when it is empty (202, 204). */
export function postJson<T = void>(path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
  return request<T>(path, 'POST', body === undefined ? undefined : JSON.stringify(body), options)
}

export function patchJson<T = void>(path: string, body: unknown, options: RequestOptions = {}): Promise<T> {
  return request<T>(path, 'PATCH', JSON.stringify(body), options)
}

export function deleteJson<T = void>(path: string, options: RequestOptions = {}): Promise<T> {
  return request<T>(path, 'DELETE', undefined, options)
}

/**
 * Downloads a file (e.g. a CSV export) with fetch, so it carries the business header a plain link
 * can't, then hands it to the browser as a download.
 */
export async function downloadFile(path: string, fallbackName: string, signal?: AbortSignal): Promise<void> {
  const response = await send(path, 'GET', undefined, { signal }, '*/*')
  const blob = await response.blob()
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const name = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition)?.[1]
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = name ? decodeURIComponent(name) : fallbackName
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  // Revoke once the browser has picked up the download.
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

async function request<T>(url: string, method: string, body: BodyInit | undefined, options: RequestOptions): Promise<T> {
  const response = await send(url, method, body, options, 'application/json')
  if (response.status === 202 || response.status === 204) return undefined as T
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

async function send(
  url: string,
  method: string,
  body: BodyInit | undefined,
  options: RequestOptions,
  accept: string,
): Promise<Response> {
  const headers: Record<string, string> = { Accept: accept }
  if (typeof body === 'string') headers['Content-Type'] = 'application/json'
  const businessId = options.businessId === undefined ? selectedBusinessId : options.businessId
  if (businessId !== null) headers['X-Business-Id'] = String(businessId)

  let response: Response
  try {
    if (method !== 'GET') {
      const token = await csrfToken(options.signal)
      if (token) headers[CSRF_HEADER] = token
    }
    response = await fetch(url, { method, body, headers, signal: options.signal, credentials: 'same-origin' })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, UNREACHABLE)
  }

  if (!response.ok) throw await errorFrom(response, options)
  return response
}

async function errorFrom(response: Response, options: RequestOptions): Promise<ApiError> {
  const { status } = response
  let detail: string | undefined
  try {
    detail = ((await response.json()) as ProblemDetail).detail
  } catch {
    // Not a problem-detail body (e.g. the dev proxy could not connect).
  }
  let message = detail ?? `Request failed (${status}).`
  if (!detail) {
    if (status === 502 || status === 504) message = UNREACHABLE
    else if (status === 401) message = 'Sign in to continue.'
    else if (status === 403) message = FORBIDDEN
    else if (status === 429) message = 'Too many attempts. Wait a few minutes and try again.'
  }
  const retryAfter = Number(response.headers.get('Retry-After'))
  if (status === 401 && !options.expectUnauthorized) {
    // The session ended (expired, signed out elsewhere, password reset): let the app send the
    // user to the sign-in page.
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
  }
  return new ApiError(status, message, Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : null)
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
