import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import {
  IMPORT_LIMITS,
  importsApi,
  type ImportKind,
  type ImportMapping,
  type ImportMode,
  type ImportOptions,
  type ImportPreview,
  type ImportResult,
} from '../../api/imports'
import { useApi } from '../../hooks/useApi'
import { formatCurrency, formatNumber } from '../../lib/format'
import type { Filters } from '../../lib/filters'
import { Link } from '../Link'
import { ErrorState, Panel } from '../Panel'
import { MappingFields, PreviewTable } from './ColumnMapping'
import { DownloadButton } from './DownloadButton'
import { CategoryChangeNote, ImportCounts, ImportErrorsTable, ImportStatusBadge } from './ImportResultView'
import { countOf, hasModes, KINDS, MODE_LABELS } from './kinds'
import { completeMapping, initialMapping, mappingProblems, mappingSelectName } from './mapping'
import { importedSalesLink } from './salesRange'

export type ImportBusy = 'reading' | 'validating' | 'importing' | null

interface ImportFlowProps {
  kind: ImportKind
  currency: string
  timeZone: string
  href: (path: string) => string
  onFiltersChange: (filters: Filters) => void
  /** Kept by the page, so the type can't change while a file is being sent. */
  busy: ImportBusy
  onBusy: (busy: ImportBusy) => void
  /** Called after a file was imported, so the history and the reporting context can reload. */
  onImported: () => void
}

/**
 * Steps 2–4 for one import type: choose a file → preview and match columns (and, for stores and
 * products, the mode) → Validate (dry run) → Import. The browser keeps the file and sends it with
 * every request; the API re-validates it on import, so nothing is written unless it is still valid.
 * The page keys this component by type, so changing type starts over.
 */
export function ImportFlow({ kind, currency, timeZone, href, onFiltersChange, busy, onBusy, onImported }: ImportFlowProps) {
  const [file, setFile] = useState<File | null>(null)
  const [inputKey, setInputKey] = useState(0)
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  const [fileError, setFileError] = useState<UploadError | null>(null)
  const [mapping, setMapping] = useState<ImportMapping>({})
  const [mode, setMode] = useState<ImportMode>('create_only')
  // Required-field messages appear once the user tried to validate.
  const [attempted, setAttempted] = useState(false)
  const [result, setResult] = useState<ImportResult | null>(null)
  const [runError, setRunError] = useState<UploadError | null>(null)
  const controller = useRef<AbortController | null>(null)
  const mappingForm = useRef<HTMLFormElement>(null)
  const outcome = useRef<HTMLDivElement>(null)

  // Abort a request still in flight when leaving the page or changing type.
  useEffect(() => () => controller.current?.abort(), [])

  // Bring a new validation or import result into view; it appears below the mapping.
  useEffect(() => {
    if (result || runError) outcome.current?.scrollIntoView({ block: 'nearest' })
  }, [result, runError])

  const options = (): ImportOptions => ({
    mapping: preview ? completeMapping(preview.fields, mapping) : undefined,
    mode: hasModes(kind) ? mode : undefined,
  })

  /** Starts a request, aborting any earlier one; resolves to null when aborted or superseded. */
  const track = async <T,>(step: Exclude<ImportBusy, null>, run: (signal: AbortSignal) => Promise<T>) => {
    controller.current?.abort()
    const current = new AbortController()
    controller.current = current
    onBusy(step)
    try {
      return { value: await run(current.signal) }
    } catch (err) {
      if (current.signal.aborted) return null
      return { error: err }
    } finally {
      if (controller.current === current) {
        controller.current = null
        onBusy(null)
      }
    }
  }

  const chooseFile = async (next: File | null) => {
    controller.current?.abort()
    setFile(next)
    setPreview(null)
    setMapping({})
    setAttempted(false)
    setResult(null)
    setRunError(null)
    setFileError(null)
    if (!next) return
    if (next.size > IMPORT_LIMITS.maxBytes) {
      setFileError({ message: tooLargeMessage(next.size), retryable: false })
      return
    }
    const answer = await track('reading', (signal) => importsApi.preview(kind, next, signal))
    if (!answer) return
    if ('error' in answer) setFileError(uploadError(answer.error, next.size))
    else {
      setPreview(answer.value)
      setMapping(initialMapping(answer.value))
    }
  }

  const startOver = () => {
    void chooseFile(null)
    setInputKey((k) => k + 1)
  }

  // Any change to what would be sent makes an earlier validation stale.
  const invalidate = () => {
    setResult(null)
    setRunError(null)
  }

  const send = async (dryRun: boolean) => {
    if (!file || !preview) return
    setRunError(null)
    const answer = await track(dryRun ? 'validating' : 'importing', (signal) =>
      importsApi.run(kind, file, options(), dryRun, signal),
    )
    if (!answer) return
    if ('error' in answer) {
      setResult(null)
      setRunError(uploadError(answer.error, file.size))
      return
    }
    setResult(answer.value)
    if (answer.value.status === 'IMPORTED') onImported()
  }

  const imported = result?.status === 'IMPORTED' ? result : null
  const locked = busy !== null || imported !== null
  const clientProblems = preview ? mappingProblems(preview.fields, mapping, attempted) : {}
  // The API reports mapping problems as file-level errors naming the field; show them by its select.
  const serverProblems: Record<string, string> = {}
  for (const e of result?.status === 'REJECTED' ? result.errors : []) {
    if (e.line === null && e.field && !serverProblems[e.field]) serverProblems[e.field] = e.message
  }
  const fieldErrors = { ...serverProblems, ...clientProblems }

  const onValidate = (event: FormEvent) => {
    event.preventDefault()
    if (!preview) return
    setAttempted(true)
    const problems = mappingProblems(preview.fields, mapping, true)
    const first = preview.fields.find((f) => problems[f.name])
    if (first) {
      mappingForm.current?.querySelector<HTMLSelectElement>(`[name="${mappingSelectName(first.name)}"]`)?.focus()
      return
    }
    void send(true)
  }

  return (
    <>
      <Panel title="2. Choose a file" subtitle="Validate first: nothing is written until you import.">
        <div className="imports-form">
          <label className="field imports-file-field">
            <span className="field-label">{KINDS[kind].label} CSV file</span>
            <input
              key={inputKey}
              type="file"
              accept=".csv,text/csv"
              className="control imports-file-input"
              disabled={locked}
              onChange={(e) => void chooseFile(e.target.files?.[0] ?? null)}
            />
          </label>
        </div>
        <FormatHelp kind={kind} timeZone={timeZone} />
        <div className="imports-outcome" aria-live="polite">
          {busy === 'reading' && (
            <p className="imports-progress" role="status">
              <span className="imports-spinner" aria-hidden="true" />
              Reading {file?.name}…
            </p>
          )}
          {fileError && busy !== 'reading' && (
            <ErrorState
              message={fileError.message}
              onRetry={fileError.retryable && file ? () => void chooseFile(file) : undefined}
            />
          )}
        </div>
      </Panel>

      {preview && (
        <Panel title="3. Match columns" subtitle={`${preview.fileName} · ${formatNumber(preview.rowCount)} data rows`}>
          <form ref={mappingForm} className="imports-step" onSubmit={onValidate} noValidate>
            <PreviewTable preview={preview} mapping={mapping} />
            <MappingFields
              preview={preview}
              mapping={mapping}
              errors={fieldErrors}
              disabled={locked}
              onChange={(field, column) => {
                setMapping({ ...mapping, [field]: column })
                invalidate()
              }}
            />
            {hasModes(kind) && (
              <ModeChoice
                kind={kind}
                value={mode}
                disabled={locked}
                onChange={(next) => {
                  setMode(next)
                  invalidate()
                }}
              />
            )}
            <div className="imports-buttons">
              <button type="submit" className="button imports-button-primary" disabled={locked} aria-busy={busy === 'validating'}>
                {busy === 'validating' ? 'Validating…' : 'Validate'}
              </button>
              {!imported && (
                <button type="button" className="button button-secondary" disabled={busy !== null} onClick={startOver}>
                  Choose another file
                </button>
              )}
            </div>
          </form>
        </Panel>
      )}

      {preview && (busy === 'validating' || busy === 'importing' || result || runError) && (
        <div ref={outcome} className="imports-scroll-target">
          <Panel title="4. Check and import">
            <div aria-live="polite">
              {(busy === 'validating' || busy === 'importing') && (
                <p className="imports-progress" role="status">
                  <span className="imports-spinner" aria-hidden="true" />
                  {busy === 'validating' ? `Validating ${file?.name}…` : `Importing ${file?.name}…`}
                </p>
              )}

              {runError && !busy && (
                <ErrorState
                  message={runError.message}
                  error={runError.error}
                  onRetry={runError.retryable ? () => void send(true) : undefined}
                />
              )}

              {result && !busy && !imported && (
                <div className="imports-result">
                  <div className="imports-result-head">
                    <ImportStatusBadge status={result.status} />
                    <span className="imports-result-file">{result.fileName}</span>
                    {hasModes(kind) && <span className="chip">{MODE_LABELS[result.mode]}</span>}
                  </div>
                  <ImportCounts result={result} currency={currency} />
                  {result.status === 'REJECTED' ? (
                    <>
                      <ImportErrorsTable
                        errors={result.errors}
                        errorCount={result.errorCount}
                        fields={preview.fields}
                        actions={
                          result.errors.some((e) => e.line !== null) || result.errorCount > result.errors.length ? (
                            <DownloadButton
                              download={(signal) => importsApi.downloadErrors(kind, file!, options(), signal)}
                              label="Download errors CSV"
                              busyLabel="Preparing CSV…"
                            />
                          ) : undefined
                        }
                      />
                      <p className="imports-hint">
                        {result.dryRun
                          ? 'Fix the file (or the column matching above), then validate again.'
                          : 'This attempt is recorded in the history as rejected. Fix the file, then validate again.'}
                      </p>
                    </>
                  ) : (
                    <ReadyToImport result={result} currency={currency} onImport={() => void send(false)} onCancel={startOver} />
                  )}
                </div>
              )}

              {imported && !busy && (
                <ImportSuccess
                  result={imported}
                  currency={currency}
                  timeZone={timeZone}
                  href={href}
                  onFiltersChange={onFiltersChange}
                  onStartOver={startOver}
                />
              )}
            </div>
          </Panel>
        </div>
      )}
    </>
  )
}

function ModeChoice({
  kind,
  value,
  disabled,
  onChange,
}: {
  kind: ImportKind
  value: ImportMode
  disabled: boolean
  onChange: (mode: ImportMode) => void
}) {
  const key = kind === 'stores' ? 'store code' : 'SKU'
  const updateHelp =
    kind === 'stores' ? (
      <>Rows whose store code exists replace that store's name and city (an unmapped city is left as it is). Past sales don't change.</>
    ) : (
      <>
        Rows whose SKU exists replace that product's name, category and list price. Prices charged on past sales never change,
        so revenue stays the same; a new category moves the product's past sales to that category in reports.
      </>
    )
  return (
    <fieldset className="imports-modes" disabled={disabled}>
      <legend className="imports-subheading">When a {key} already exists</legend>
      <label className={`imports-mode ${value === 'create_only' ? 'is-selected' : ''}`}>
        <input type="radio" name="import-mode" checked={value === 'create_only'} onChange={() => onChange('create_only')} />
        <span>
          <span className="imports-mode-label">{MODE_LABELS.create_only}</span>
          <span className="imports-mode-help">
            Only new {KINDS[kind].nounPlural} are added; a row for an existing {key} is an error and nothing is written.
          </span>
        </span>
      </label>
      <label className={`imports-mode ${value === 'create_or_update' ? 'is-selected' : ''}`}>
        <input
          type="radio"
          name="import-mode"
          checked={value === 'create_or_update'}
          onChange={() => onChange('create_or_update')}
        />
        <span>
          <span className="imports-mode-label">{MODE_LABELS.create_or_update}</span>
          <span className="imports-mode-help">{updateHelp}</span>
        </span>
      </label>
    </fieldset>
  )
}

function ReadyToImport({
  result,
  currency,
  onImport,
  onCancel,
}: {
  result: ImportResult
  currency: string
  onImport: () => void
  onCancel: () => void
}) {
  const { kind } = result
  let summary: string
  let button: string
  if (kind === 'sales') {
    summary = `Importing adds ${countOf(kind, result.saleCount, formatNumber)} totalling ${formatCurrency(result.totalAmount, currency)}`
    button = `Import ${countOf(kind, result.saleCount, formatNumber)}`
  } else if (result.mode === 'create_only') {
    summary = `Importing creates ${countOf(kind, result.created, formatNumber)}`
    button = `Import ${countOf(kind, result.created, formatNumber)}`
  } else {
    summary =
      `Importing creates ${countOf(kind, result.created, formatNumber)} and updates ${formatNumber(result.updated)}` +
      (result.unchanged > 0 ? ` (${formatNumber(result.unchanged)} unchanged)` : '')
    button = 'Import'
  }
  return (
    <div className="imports-actions">
      <CategoryChangeNote count={result.categoryChanges} done={false} />
      <p className="imports-hint">The file is valid. {summary} in one step; it can't be undone from the app.</p>
      <div className="imports-buttons">
        <button type="button" className="button imports-button-primary" onClick={onImport}>
          {button}
        </button>
        <button type="button" className="button button-secondary" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </div>
  )
}

function ImportSuccess({
  result,
  currency,
  timeZone,
  href,
  onFiltersChange,
  onStartOver,
}: {
  result: ImportResult
  currency: string
  timeZone: string
  href: (path: string) => string
  onFiltersChange: (filters: Filters) => void
  onStartOver: () => void
}) {
  const { kind, batchId } = result
  return (
    <div className="imports-success" role="status">
      <div className="imports-result-head">
        <ImportStatusBadge status="IMPORTED" />
        <span className="imports-result-file">{result.fileName}</span>
      </div>
      {kind === 'sales' ? (
        <p className="imports-success-text">
          Imported {countOf(kind, result.saleCount, formatNumber)} with {formatNumber(result.lineCount)} line{' '}
          {result.lineCount === 1 ? 'item' : 'items'}, totalling <strong>{formatCurrency(result.totalAmount, currency)}</strong>.
          They now count in every report.
        </p>
      ) : (
        <>
          <p className="imports-success-text">
            Created <strong>{countOf(kind, result.created, formatNumber)}</strong>
            {result.mode === 'create_or_update' && (
              <>
                {' '}
                and updated <strong>{formatNumber(result.updated)}</strong>
                {result.unchanged > 0 && <>; {formatNumber(result.unchanged)} unchanged</>}
              </>
            )}
            . Reports and filters include them now.
          </p>
          <CategoryChangeNote count={result.categoryChanges} done />
        </>
      )}
      <div className="imports-buttons">
        {batchId !== null && (
          <Link className="button imports-button-primary" href={href(`/imports/${batchId}`)}>
            View import #{batchId}
          </Link>
        )}
        {kind === 'sales' && batchId !== null ? (
          <SalesLink batchId={batchId} timeZone={timeZone} href={href} onFiltersChange={onFiltersChange} />
        ) : (
          <Link className="button button-secondary" href={href(KINDS[kind].listPath)}>
            Go to {KINDS[kind].label}
          </Link>
        )}
        <button type="button" className="button button-secondary" onClick={onStartOver}>
          Import another file
        </button>
      </div>
    </div>
  )
}

/** "View sales on these dates", once the import's date range is known. */
function SalesLink({
  batchId,
  timeZone,
  href,
  onFiltersChange,
}: {
  batchId: number
  timeZone: string
  href: (path: string) => string
  onFiltersChange: (filters: Filters) => void
}) {
  const detail = useApi(`import|${batchId}`, (signal) => importsApi.detail(batchId, signal))
  const range =
    detail.data?.firstSoldAt && detail.data.lastSoldAt
      ? importedSalesLink(detail.data.firstSoldAt, detail.data.lastSoldAt, timeZone, onFiltersChange)
      : null
  return range ? (
    <Link className="button button-secondary" href={range.href} onClick={range.onClick}>
      View sales on these dates
    </Link>
  ) : (
    <Link className="button button-secondary" href={href('/sales')}>
      Go to Sales
    </Link>
  )
}

function FormatHelp({ kind, timeZone }: { kind: ImportKind; timeZone: string }) {
  const rows =
    kind === 'sales'
      ? `${formatNumber(IMPORT_LIMITS.maxRows)} rows`
      : `${formatNumber(IMPORT_LIMITS.maxCatalogRows)} rows`
  return (
    <details className="imports-help">
      <summary>File format</summary>
      <ul>
        <li>A header row, then one row per {kind === 'sales' ? 'line item' : KINDS[kind].noun}. Columns can have any names and order: you match them to fields after choosing the file.</li>
        {kind === 'sales' && (
          <>
            <li>Rows with the same store code and receipt number form one receipt.</li>
            <li>
              Sold at as ISO-8601, e.g. <code className="imports-code">2026-09-01T14:30:00-04:00</code>; without an offset it
              is read in {timeZone.replaceAll('_', ' ')} time.
            </li>
            <li>Store codes and SKUs must already exist; receipts that already exist are never overwritten.</li>
          </>
        )}
        {kind === 'stores' && (
          <li>
            Code: 1–50 letters, digits, <code className="imports-code">. _ -</code>, unique in the file; name up to 200
            characters; city optional.
          </li>
        )}
        {kind === 'products' && (
          <li>
            SKU: 1–50 letters, digits, <code className="imports-code">. _ -</code>, unique in the file; name and category
            required; list price a number ≥ 0 with at most 2 decimals, e.g. <code className="imports-code">24.50</code>.
          </li>
        )}
        <li>UTF-8, up to 5 MB and {rows}. All or nothing: any error and nothing is written. A file can be imported only once.</li>
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
  /** The caught error: a plan-limit refusal (monthly imports, stores) links to the plans. */
  error?: unknown
}

function uploadError(err: unknown, bytes: number): UploadError {
  if (err instanceof ApiError) {
    if (err.status === 413) return { message: tooLargeMessage(bytes), retryable: false }
    // A 403 (role without imports, or the read-only demo) carries the server's explanation.
    return { message: err.message, retryable: err.status === 0 || err.status >= 500, error: err }
  }
  return { message: err instanceof Error ? err.message : String(err), retryable: true }
}
