import { downloadFile, downloadForm, getJson, sendForm } from './client'

// CSV import API (docs/catalog-imports-contract.md §4): OWNER and ADMIN of a signed-in business
// (context.access.canImport); the API answers 403 to viewers and to the read-only public demo.

export type ImportKind = 'sales' | 'stores' | 'products'

export const IMPORT_KINDS: ImportKind[] = ['sales', 'stores', 'products']

/** Stores and products only; sales imports always create. */
export type ImportMode = 'create_only' | 'create_or_update'

export type ImportStatus = 'VALIDATED' | 'IMPORTED' | 'REJECTED'

/** Field name → source column header in the file; null leaves an optional field unmapped. */
export type ImportMapping = Record<string, string | null>

export interface ImportErrorItem {
  /** 1-based physical line (the header is line 1); null for problems with the whole file. */
  line: number | null
  /** Target field, e.g. `list_price`; null when the problem isn't about one field. */
  field: string | null
  /** Source column header in the file; null when the problem concerns the whole row or file. */
  column: string | null
  message: string
}

export interface ImportResult {
  /** Set only when status is IMPORTED. */
  batchId: number | null
  kind: ImportKind
  mode: ImportMode
  status: ImportStatus
  dryRun: boolean
  fileName: string
  rowCount: number
  /** Stores or products created; for sales, receipts created. */
  created: number
  /** Existing stores or products changed (create_or_update only); 0 for sales. */
  updated: number
  /** Existing stores or products identical to their row; 0 for sales. */
  unchanged: number
  /** Updated products whose category changes (their past sales move with them). */
  categoryChanges: number
  /** Sales only (0 otherwise). */
  saleCount: number
  lineCount: number
  totalAmount: number
  /** The first 100 errors, by line. */
  errors: ImportErrorItem[]
  /** Total number of errors, which may exceed errors.length. */
  errorCount: number
}

export interface ImportField {
  name: string
  label: string
  required: boolean
  description: string
}

export interface ImportPreview {
  kind: ImportKind
  fileName: string
  rowCount: number
  /** Header cells, in file order. */
  columns: string[]
  /** The first rows (at most 10), one cell per column. */
  sampleRows: string[][]
  fields: ImportField[]
  suggestedMapping: ImportMapping
}

/** History records only real attempts: imported files and rejected ones (nothing written). */
export type ImportBatchStatus = 'IMPORTED' | 'REJECTED'

export interface ImportBatchSummary {
  batchId: number
  kind: ImportKind
  mode: ImportMode
  status: ImportBatchStatus
  fileName: string
  rowCount: number
  created: number
  updated: number
  unchanged: number
  errorCount: number
  /** Sales only (0 otherwise). */
  saleCount: number
  lineCount: number
  totalAmount: number
  /** Display name of who ran the import; null when unknown (e.g. older imports). */
  importedBy: string | null
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
  /** Sales imports only; null for other kinds and rejected files. */
  firstSoldAt: string | null
  lastSoldAt: string | null
}

export interface ImportOptions {
  /** Omitted: each field maps to the column of the same name. */
  mapping?: ImportMapping
  /** Stores and products; sales accept only create_only. */
  mode?: ImportMode
}

/** Mirrors the API's limits, for help text and a quick client-side size check. */
export const IMPORT_LIMITS = {
  maxBytes: 5 * 1024 * 1024,
  maxRows: 50_000,
  maxCatalogRows: 5_000,
  maxReportedErrors: 100,
}

export const IMPORT_COLUMNS = ['store_code', 'receipt_number', 'sold_at', 'sku', 'quantity', 'unit_price'] as const

/** The multipart body shared by imports and their errors CSV. */
function importForm(file: File, { mapping, mode }: ImportOptions, dryRun?: boolean): FormData {
  const form = new FormData()
  form.append('file', file)
  if (mapping) form.append('mapping', JSON.stringify(mapping))
  if (mode) form.append('mode', mode)
  if (dryRun !== undefined) form.append('dryRun', String(dryRun))
  return form
}

export const importsApi = {
  templateUrl: (kind: ImportKind) => `/api/imports/templates/${kind}.csv`,

  /** Saves the CSV template of `kind` (header plus example rows). */
  downloadTemplate: (kind: ImportKind, signal?: AbortSignal) =>
    downloadFile(importsApi.templateUrl(kind), `${kind}-template.csv`, signal),

  /** Reads the header and first rows, and suggests a column for each field. 400 for an unreadable file. */
  preview: (kind: ImportKind, file: File, signal?: AbortSignal) => {
    const form = new FormData()
    form.append('file', file)
    return sendForm<ImportPreview>(`/api/imports/${kind}/preview`, form, {}, signal)
  },

  /** Validates the file, and imports it when `dryRun` is false and it has no errors. */
  run: (kind: ImportKind, file: File, options: ImportOptions, dryRun: boolean, signal?: AbortSignal) =>
    sendForm<ImportResult>(`/api/imports/${kind}`, importForm(file, options, dryRun), {}, signal),

  /** Saves the rejected rows with their errors as CSV (header only when the file has none). */
  downloadErrors: (kind: ImportKind, file: File, options: ImportOptions, signal?: AbortSignal) =>
    downloadForm(`/api/imports/${kind}/errors.csv`, importForm(file, options), `${kind}-errors.csv`, {}, signal),

  list: (page: number, size: number, kind: ImportKind | null, signal?: AbortSignal) =>
    getJson<ImportListResponse>('/api/imports', { page, size, kind }, signal),

  upload: (file: File, dryRun: boolean, signal?: AbortSignal) => importsApi.run('sales', file, {}, dryRun, signal),

  detail: (batchId: number, signal?: AbortSignal) => getJson<ImportDetail>(`/api/imports/${batchId}`, {}, signal),
}
