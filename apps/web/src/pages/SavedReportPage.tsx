import { useState } from 'react'
import { ApiError } from '../api/client'
import { savedReportsApi, savedReportInput, type SavedReport, type SavedReportRun } from '../api/savedReports'
import { Dialog } from '../components/Dialog'
import { FormError, FormSuccess } from '../components/Form'
import { Link } from '../components/Link'
import { PageHeader } from '../components/PageHeader'
import { EmptyState, ErrorState, Panel, Skeleton, SkeletonRows } from '../components/Panel'
import { CategoryReportTable } from '../components/reports/CategoryReportTable'
import { ExportLinks } from '../components/reports/ExportLink'
import { MonthlyReportTable } from '../components/reports/MonthlyReportTable'
import { SavedReportForm } from '../components/reports/SavedReportForm'
import { useApi } from '../hooks/useApi'
import { formatDateRange } from '../lib/format'
import { navigate } from '../lib/router'
import { KIND_LABELS, rangeLabel, rollingExplanation, savedReportPermissions, storeLabel } from '../lib/savedReports'
import { errorMessage } from '../lib/validation'
import '../styles/accounts.css'
import '../styles/reports.css'
import { VerifyToManage } from './SavedReportsPage'
import type { PageProps } from './types'

type Notice = { kind: 'success' | 'error'; message: string } | null

/** One saved report, run for its range resolved today. */
export function SavedReportPage({ savedReportId, context, href }: PageProps & { savedReportId: number }) {
  const { business, stores } = context
  const can = savedReportPermissions(context.access)
  const [version, setVersion] = useState(0)
  const [notice, setNotice] = useState<Notice>(null)
  const [editing, setEditing] = useState(false)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const run = useApi(`saved-report|${savedReportId}|${version}`, (signal) => savedReportsApi.run(savedReportId, signal))
  const listHref = href('/reports/saved')

  if (run.error instanceof ApiError && run.error.status === 404) {
    return <SavedReportNotFound listHref={listHref} />
  }

  const report = run.data?.savedReport

  const remove = async () => {
    setDeleting(true)
    try {
      await savedReportsApi.remove(savedReportId)
      navigate(listHref)
    } catch (err) {
      setNotice({ kind: 'error', message: errorMessage(err) })
      setConfirmingDelete(false)
      setDeleting(false)
    }
  }

  return (
    <>
      <PageHeader
        eyebrow={
          <nav aria-label="Breadcrumb" className="breadcrumb">
            <Link href={href('/reports')}>Reports</Link>
            <span aria-hidden="true">/</span>
            <Link href={listHref}>Saved</Link>
          </nav>
        }
        title={report ? <span className="break-anywhere">{report.name}</span> : <Skeleton height={30} width={240} />}
        subtitle={report ? <SavedReportDetails report={report} timeZone={business.timeZone} /> : undefined}
      >
        {report && can.canManage && (
          <div className="saved-actions">
            {confirmingDelete ? (
              <span className="confirm-inline" role="group" aria-label={`Delete ${report.name}?`}>
                <span className="confirm-text">Delete this saved report?</span>
                <button type="button" className="button button-danger" disabled={deleting} onClick={() => void remove()}>
                  {deleting ? 'Deleting…' : 'Delete'}
                </button>
                <button
                  type="button"
                  className="button button-secondary"
                  disabled={deleting}
                  onClick={() => setConfirmingDelete(false)}
                >
                  Cancel
                </button>
              </span>
            ) : (
              <>
                <button type="button" className="button button-secondary" aria-haspopup="dialog" onClick={() => setEditing(true)}>
                  Edit
                </button>
                <button type="button" className="button button-secondary" onClick={() => setConfirmingDelete(true)}>
                  Delete
                </button>
              </>
            )}
          </div>
        )}
      </PageHeader>

      {can.needsVerification && <VerifyToManage />}

      <div aria-live="polite">
        {notice?.kind === 'success' && <FormSuccess>{notice.message}</FormSuccess>}
        {notice?.kind === 'error' && <FormError>{notice.message}</FormError>}
      </div>

      {run.error ? (
        <div className="panel page-error">
          <ErrorState message={run.error.message} onRetry={run.retry} />
        </div>
      ) : !run.data ? (
        <Panel title="Report">
          <SkeletonRows rows={6} />
        </Panel>
      ) : (
        <SavedReportResult run={run.data} loading={run.loading} currency={business.currency} timeZone={business.timeZone} />
      )}

      {report && can.canManage && (
        <Dialog open={editing} title="Edit saved report" onClose={() => setEditing(false)}>
          <SavedReportForm
            mode="edit"
            initial={savedReportInput(report)}
            stores={stores}
            timeZone={business.timeZone}
            suggestedPreset="last_30_days"
            suggestedDates={report.period}
            submitLabel="Save changes"
            busyLabel="Saving…"
            onSubmit={async (input) => {
              const updated = await savedReportsApi.update(report.id, input)
              setEditing(false)
              setNotice({ kind: 'success', message: `Saved. “${updated.name}” now covers ${formatDateRange(updated.period.from, updated.period.to)}.` })
              setVersion((v) => v + 1)
            }}
            onCancel={() => setEditing(false)}
          />
        </Dialog>
      )}
    </>
  )
}

/** "Monthly report · Previous quarter · Jul 1 – Sep 30, 2026 · Boston · America/New_York" */
function SavedReportDetails({ report, timeZone }: { report: SavedReport; timeZone: string }) {
  return (
    <span className="saved-details">
      <span>{KIND_LABELS[report.kind]}</span>
      <span>{rangeLabel(report.range)}</span>
      <span className="nowrap">{formatDateRange(report.period.from, report.period.to)}</span>
      <span className="break-anywhere">{storeLabel(report)}</span>
      <span className="page-subtitle-muted">{timeZone}</span>
      {report.range.type === 'relative' && <span className="saved-details-note">{rollingExplanation(timeZone)}</span>}
    </span>
  )
}

function SavedReportResult({
  run,
  loading,
  currency,
  timeZone,
}: {
  run: SavedReportRun
  loading: boolean
  currency: string
  timeZone: string
}) {
  const { savedReport } = run
  const label = KIND_LABELS[savedReport.kind].toLowerCase()
  const exports = (
    <ExportLinks
      csvHref={savedReportsApi.exportUrl(savedReport.id, 'csv')}
      pdfHref={savedReportsApi.exportUrl(savedReport.id, 'pdf')}
      label={label}
    />
  )

  if (run.monthly) {
    return (
      <Panel title="Monthly report" subtitle={`Calendar months in ${timeZone}`} actions={exports}>
        <div className={loading ? 'is-refreshing' : undefined} aria-busy={loading}>
          {run.monthly.totals.orders === 0 ? (
            <EmptyState message="No sales in this period." />
          ) : (
            <MonthlyReportTable report={run.monthly} currency={currency} />
          )}
        </div>
      </Panel>
    )
  }
  const categories = run.categories
  return (
    <Panel title="Category report" subtitle="Every catalogue category, by revenue" actions={exports}>
      <div className={loading ? 'is-refreshing' : undefined} aria-busy={loading}>
        {categories.totals.orders === 0 ? (
          <EmptyState
            message={categories.rows.length === 0 ? 'There are no products in the catalogue yet.' : 'No sales in this period.'}
          />
        ) : (
          <CategoryReportTable report={categories} currency={currency} />
        )}
      </div>
    </Panel>
  )
}

function SavedReportNotFound({ listHref }: { listHref: string }) {
  return (
    <div className="panel page-error">
      <h1 className="page-title">Saved report not found</h1>
      <ErrorState message="It may have been deleted, or it belongs to another business." />
      <p className="page-error-action">
        <Link className="button button-secondary" href={listHref}>
          See saved reports
        </Link>
      </p>
    </div>
  )
}
