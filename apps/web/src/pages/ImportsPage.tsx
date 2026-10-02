import { useState } from 'react'
import type { ImportKind } from '../api/imports'
import { ImportFlow, type ImportBusy } from '../components/imports/ImportFlow'
import { ImportHistory } from '../components/imports/ImportHistory'
import { ImportTypePicker } from '../components/imports/ImportTypePicker'
import { DEFAULT_KIND, kindFromUrl } from '../components/imports/kinds'
import { PageHeader } from '../components/PageHeader'
import { useStateResetOn } from '../hooks/useStateResetOn'
import { updateQuery, useHistoryVersion } from '../lib/router'
import '../styles/imports.css'
import type { PageProps } from './types'

/**
 * CSV imports of sales, stores and products: choose the type, match the file's columns, validate
 * (dry run), import all-or-nothing, and browse earlier attempts. For OWNER and ADMIN
 * (context.access.canImport); the API refuses everyone else.
 */
export function ImportsPage({ context, onFiltersChange, href, refreshContext }: PageProps) {
  const { business } = context
  // The type lives in `?type=` so refresh, shared links and back/forward restore it.
  const historyVersion = useHistoryVersion()
  const [kind, setKindState] = useStateResetOn<ImportKind>(String(historyVersion), kindFromUrl())
  const setKind = (next: ImportKind) => {
    setKindState(next)
    updateQuery({ type: next !== DEFAULT_KIND ? next : null })
  }
  const [busy, setBusy] = useState<ImportBusy>(null)
  const [historyPage, setHistoryPage] = useState(0)
  const [historyKind, setHistoryKind] = useState<ImportKind | null>(null)
  const [historyRefresh, setHistoryRefresh] = useState(0)

  const onImported = () => {
    // Show the new import at the top of the history.
    setHistoryPage(0)
    setHistoryRefresh((v) => v + 1)
    // Every kind changes what reports see: new dates (sales), the store list (stores), categories
    // (products). Refresh the context so filters and presets follow without a reload.
    refreshContext()
  }

  return (
    <>
      <PageHeader eyebrow={business.name} title="Import" subtitle="Load sales, stores or products from CSV files" />

      <ImportTypePicker value={kind} onChange={setKind} disabled={busy !== null} />

      {/* Keyed by type: switching type drops the file, mapping and results. */}
      <ImportFlow
        key={kind}
        kind={kind}
        currency={business.currency}
        timeZone={business.timeZone}
        href={href}
        onFiltersChange={onFiltersChange}
        busy={busy}
        onBusy={setBusy}
        onImported={onImported}
      />

      <ImportHistory
        page={historyPage}
        onPage={setHistoryPage}
        kind={historyKind}
        onKind={(next) => {
          setHistoryKind(next)
          setHistoryPage(0)
        }}
        refreshKey={historyRefresh}
        currency={business.currency}
        timeZone={business.timeZone}
        batchHref={(id) => href(`/imports/${id}`)}
      />
    </>
  )
}
