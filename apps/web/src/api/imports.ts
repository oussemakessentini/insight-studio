import { getJson, sendForm } from './client'

// CSV import API: OWNER and ADMIN of a signed-in business (context.access.canImport); the API
// answers 403 to viewers and to the read-only public demo.

export type ImportStatus = 'VALIDATED' | 'IMPORTED' | 'REJECTED'

export interface ImportErrorItem {
  /** 1-based physical line (the header is line 1); null for problems with the whole file. */
  line: number | null
  /** CSV column name; null when the problem concerns the whole row or file. */
  column: string | null
  message: string
}

export interface ImportResult {
  /** Set only when status is IMPORTED. */
  batchId: number | null
  status: ImportStatus
  dryRun: boolean
  fileName: string
  rowCount: number
  saleCount: number
  lineCount: number
  totalAmount: number
  /** The first 100 errors, by line. */
  errors: ImportErrorItem[]
  /** Total number of errors, which may exceed errors.length. */
  errorCount: number
}

export interface ImportBatchSummary {
  batchId: number
  fileName: string
  rowCount: number
  saleCount: number
  lineCount: number
  totalAmount: number
  createdAt: string
}

export interface ImportListResponse {
  page: number
  size: number
  totalItems: number
  totalPages: number
  items: ImportBatchSummary[]
}

export interface ImportDetail extends ImportBatchSummary {
  firstSoldAt: string | null
  lastSoldAt: string | null
}

/** Mirrors the API's limits, for help text and a quick client-side size check. */
export const IMPORT_LIMITS = { maxBytes: 5 * 1024 * 1024, maxRows: 50_000, maxReportedErrors: 100 }

export const IMPORT_COLUMNS = ['store_code', 'receipt_number', 'sold_at', 'sku', 'quantity', 'unit_price'] as const

export const importsApi = {
  /** Validates the file, and imports it when `dryRun` is false and it has no errors. */
  upload: (file: File, dryRun: boolean, signal?: AbortSignal) => {
    const form = new FormData()
    form.append('file', file)
    return sendForm<ImportResult>('/api/imports', form, { dryRun }, signal)
  },

  list: (page: number, size: number, signal?: AbortSignal) =>
    getJson<ImportListResponse>('/api/imports', { page, size }, signal),

  detail: (batchId: number, signal?: AbortSignal) => getJson<ImportDetail>(`/api/imports/${batchId}`, {}, signal),
}
