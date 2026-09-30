import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { IMPORT_COLUMNS, IMPORT_LIMITS, importsApi, type ImportResult } from '../../api/imports'
import { useApi } from '../../hooks/useApi'
import { formatCurrency, formatNumber } from '../../lib/format'
import type { Filters } from '../../lib/filters'
import { Link } from '../Link'
import { ErrorState, Panel } from '../Panel'
import { ImportCounts, ImportErrorsTable, ImportStatusBadge } from './ImportResultView'
import { importedSalesLink } from './salesRange'

interface ImportUploaderProps {
  currency: string
  timeZone: string
  href: (path: string) => string
  onFiltersChange: (filters: Filters) => void
  /** Called after a file was imported, so the history can reload. */
  onImported: () => void
}

type Busy = 'validating' | 'importing' | null

/**
 * Pick a file → Validate (dry run) → review counts or errors → Import. The file is sent again for
 * the real import and re-validated by the API, so nothing is written unless it is still valid.
 */
export function ImportUploader({ currency, timeZone, href, onFiltersChange, onImported }: ImportUploaderProps) {
  const [file, setFile] = useState<File | null>(null)
  const [inputKey, setInputKey] = useState(0)
  const [busy, setBusy] = useState<Busy>(null)
  const [result, setResult] = useState<ImportResult | null>(null)
  const [error, setError] = useState<UploadError | null>(null)
  const controller = useRef<AbortController | null>(null)

  // Abort an upload still in flight when leaving the page.
  useEffect(() => () => controller.current?.abort(), [])

  const chooseFile = (next: File | null) => {
    controller.current?.abort()
    setBusy(null)
    setFile(next)
    setResult(null)
    setError(next && next.size > IMPORT_LIMITS.maxBytes ? { message: tooLargeMessage(next.size), retryable: false } : null)
  }

  const startOver = () => {
    chooseFile(null)
    setInputKey((k) => k + 1)
  }

  const send = async (dryRun: boolean) => {
    if (!file) return
    controller.current?.abort()
    const current = new AbortController()
    controller.current = current
    setBusy(dryRun ? 'validating' : 'importing')
    setError(null)
    try {
      const response = await importsApi.upload(file, dryRun, current.signal)
      setResult(response)
      if (response.status === 'IMPORTED') onImported()
    } catch (err) {
      if (current.signal.aborted) return
      setResult(null)
      setError(uploadError(err, file.size))
    } finally {
      if (controller.current === current) {
        controller.current = null
        setBusy(null)
      }
    }
  }

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    void send(true)
  }

  const imported = result?.status === 'IMPORTED' ? result : null

  return (
    <Panel title="Upload a CSV file" subtitle="Validate first: nothing is written until you import.">
      <form className="imports-form" onSubmit={onSubmit}>
        <label className="field imports-file-field">
          <span className="field-label">CSV file</span>
          <input
            key={inputKey}
            type="file"
            accept=".csv,text/csv"
            className="control imports-file-input"
            disabled={busy !== null}
            onChange={(e) => chooseFile(e.target.files?.[0] ?? null)}
          />
        </label>
        <button type="submit" className="button imports-button-primary" disabled={!file || busy !== null || imported !== null}>
          {busy === 'validating' ? 'Validating…' : 'Validate'}
        </button>
      </form>
      <FormatHelp timeZone={timeZone} />

      <div className="imports-outcome" aria-live="polite">
        {busy && (
          <p className="imports-progress" role="status">
            <span className="imports-spinner" aria-hidden="true" />
            {busy === 'validating' ? `Validating ${file?.name}…` : `Importing ${file?.name}…`}
          </p>
        )}

        {error && !busy && <ErrorState message={error.message} onRetry={error.retryable ? () => void send(true) : undefined} />}

        {result && !busy && !imported && (
          <div className="imports-result">
            <div className="imports-result-head">
              <ImportStatusBadge status={result.status} />
              <span className="imports-result-file">{result.fileName}</span>
            </div>
            <ImportCounts result={result} currency={currency} />
            {result.status === 'REJECTED' ? (
              <>
                <ImportErrorsTable errors={result.errors} errorCount={result.errorCount} />
                <p className="imports-hint">Fix the file and choose it again, then validate.</p>
              </>
            ) : (
              <div className="imports-actions">
                <p className="imports-hint">
                  The file is valid. Importing adds {formatNumber(result.saleCount)}{' '}
                  {result.saleCount === 1 ? 'receipt' : 'receipts'} totalling {formatCurrency(result.totalAmount, currency)}{' '}
                  in one step; it can't be undone from the app.
                </p>
                <div className="imports-buttons">
                  <button type="button" className="button imports-button-primary" onClick={() => void send(false)}>
                    Import {formatNumber(result.saleCount)} {result.saleCount === 1 ? 'receipt' : 'receipts'}
                  </button>
                  <button type="button" className="button button-secondary" onClick={startOver}>
                    Cancel
                  </button>
                </div>
              </div>
            )}
          </div>
        )}

        {imported && imported.batchId !== null && !busy && (
          <ImportSuccess
            result={imported}
            batchId={imported.batchId}
            currency={currency}
            timeZone={timeZone}
            href={href}
            onFiltersChange={onFiltersChange}
            onStartOver={startOver}
          />
        )}
      </div>
    </Panel>
  )
}

function ImportSuccess({
  result,
  batchId,
  currency,
  timeZone,
  href,
  onFiltersChange,
  onStartOver,
}: {
  result: ImportResult
  batchId: number
  currency: string
  timeZone: string
  href: (path: string) => string
  onFiltersChange: (filters: Filters) => void
  onStartOver: () => void
}) {
  // The dates the new receipts cover, for a Sales link that actually shows them.
  const detail = useApi(`import|${batchId}`, (signal) => importsApi.detail(batchId, signal))
  const range =
    detail.data?.firstSoldAt && detail.data.lastSoldAt
      ? importedSalesLink(detail.data.firstSoldAt, detail.data.lastSoldAt, timeZone, onFiltersChange)
      : null

  return (
    <div className="imports-success" role="status">
      <div className="imports-result-head">
        <ImportStatusBadge status="IMPORTED" />
        <span className="imports-result-file">{result.fileName}</span>
      </div>
      <p className="imports-success-text">
        Imported {formatNumber(result.saleCount)} {result.saleCount === 1 ? 'receipt' : 'receipts'} with{' '}
        {formatNumber(result.lineCount)} line {result.lineCount === 1 ? 'item' : 'items'}, totalling{' '}
        <strong>{formatCurrency(result.totalAmount, currency)}</strong>. They now count in every report.
      </p>
      <div className="imports-buttons">
        <Link className="button imports-button-primary" href={href(`/imports/${batchId}`)}>
          View import #{batchId}
        </Link>
        {range ? (
          <Link className="button button-secondary" href={range.href} onClick={range.onClick}>
            View sales on these dates
          </Link>
        ) : (
          <Link className="button button-secondary" href={href('/sales')}>
            Go to Sales
          </Link>
        )}
        <button type="button" className="button button-secondary" onClick={onStartOver}>
          Import another file
        </button>
      </div>
    </div>
  )
}

function FormatHelp({ timeZone }: { timeZone: string }) {
  return (
    <details className="imports-help">
      <summary>File format</summary>
      <ul>
        <li>
          Header: <code className="imports-code">{IMPORT_COLUMNS.join(',')}</code>
        </li>
        <li>One row per line item; rows with the same store code and receipt number form one receipt.</li>
        <li>
          <code className="imports-code">sold_at</code> as ISO-8601, e.g. <code className="imports-code">2026-09-01T14:30:00-04:00</code>;
          without an offset it is read in {timeZone.replaceAll('_', ' ')} time.
        </li>
        <li>Store codes and SKUs must already exist; receipts that already exist are never overwritten.</li>
        <li>
          UTF-8, up to 5 MB and {formatNumber(IMPORT_LIMITS.maxRows)} rows. A file can be imported only once.
        </li>
      </ul>
    </details>
  )
}

function tooLargeMessage(bytes: number): string {
  return `This file is ${(bytes / (1024 * 1024)).toFixed(1)} MB; the limit is 5 MB. Split it into smaller files.`
}

interface UploadError {
  message: string
  /** Network and server errors may pass on a second try; a refused file (400, 413) won't. */
  retryable: boolean
}

function uploadError(err: unknown, bytes: number): UploadError {
  if (err instanceof ApiError) {
    if (err.status === 413) return { message: tooLargeMessage(bytes), retryable: false }
    // A 403 (role without imports, or the read-only demo) carries the server's explanation.
    return { message: err.message, retryable: err.status === 0 || err.status >= 500 }
  }
  return { message: err instanceof Error ? err.message : String(err), retryable: true }
}
