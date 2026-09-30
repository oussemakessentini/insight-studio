import { useState } from 'react'
import { ImportHistory } from '../components/imports/ImportHistory'
import { ImportUploader } from '../components/imports/ImportUploader'
import { PageHeader } from '../components/PageHeader'
import '../styles/imports.css'
import type { PageProps } from './types'

/**
 * CSV import of historical sales: validate a file (dry run), import it all-or-nothing, and browse
 * earlier imports. For OWNER and ADMIN (context.access.canImport); the API refuses everyone else.
 */
export function ImportsPage({ context, onFiltersChange, href, refreshContext }: PageProps) {
  const { business } = context
  const [historyPage, setHistoryPage] = useState(0)
  const [historyVersion, setHistoryVersion] = useState(0)

  const onImported = () => {
    // Show the new import at the top of the history.
    setHistoryPage(0)
    setHistoryVersion((v) => v + 1)
    // Imported sales can extend the data range; refresh it so date presets include the new dates.
    refreshContext()
  }

  return (
    <>
      <PageHeader
        eyebrow={business.name}
        title="Import sales"
        subtitle="Load past receipts from a CSV file"
      />

      <ImportUploader
        currency={business.currency}
        timeZone={business.timeZone}
        href={href}
        onFiltersChange={onFiltersChange}
        onImported={onImported}
      />

      <ImportHistory
        page={historyPage}
        onPage={setHistoryPage}
        refreshKey={historyVersion}
        currency={business.currency}
        timeZone={business.timeZone}
        batchHref={(id) => href(`/imports/${id}`)}
      />
    </>
  )
}
