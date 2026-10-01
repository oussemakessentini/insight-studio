import { useState, type FormEvent } from 'react'
import { savedReportsApi, savedReportInput, SAVED_REPORT_NAME_MAX, type SavedReport } from '../api/savedReports'
import { FormError, FormSuccess } from '../components/Form'
import { Link } from '../components/Link'
import { PageHeader } from '../components/PageHeader'
import { AsyncContent, ErrorState, Panel, SkeletonRows } from '../components/Panel'
import { ResendVerification } from '../components/VerifyEmailBanner'
import { useApi } from '../hooks/useApi'
import { formatDateTime } from '../lib/format'
import { signInHref } from '../lib/router'
import { KIND_LABELS, rangeDescription, savedReportPermissions, storeLabel } from '../lib/savedReports'
import { errorMessage } from '../lib/validation'
import '../styles/accounts.css'
import '../styles/reports.css'
import type { PageProps } from './types'

type Notice = { kind: 'success' | 'error'; message: string } | null

/** Every saved report of the business, re-resolved to today's dates. */
export function SavedReportsPage({ context, href }: PageProps) {
  const { business } = context
  const can = savedReportPermissions(context.access)
  const [version, setVersion] = useState(0)
  const [notice, setNotice] = useState<Notice>(null)
  const list = useApi(`saved-reports|${version}`, (signal) => savedReportsApi.list(signal))

  const afterChange = (message: string) => {
    setNotice({ kind: 'success', message })
    setVersion((v) => v + 1)
  }

  return (
    <>
      <PageHeader
        eyebrow={
          <nav aria-label="Breadcrumb" className="breadcrumb">
            <Link href={href('/reports')}>Reports</Link>
            <span aria-hidden="true">/</span>
            <span>Saved</span>
          </nav>
        }
        title="Saved reports"
        subtitle={
          <>
            Reports saved with a name, a store and dates
            <span className="page-subtitle-muted"> · Dates in {business.timeZone}</span>
          </>
        }
      >
        <Link className="button button-secondary" href={href('/reports')}>
          Go to Reports
        </Link>
      </PageHeader>

      {can.needsVerification && <VerifyToManage />}

      <Panel
        title="All saved reports"
        subtitle={
          can.canManage
            ? 'Rolling periods move forward with today’s date every time a report runs.'
            : 'Open a report to see today’s figures and export them as CSV or PDF.'
        }
      >
        <div className="form-stack">
          <div aria-live="polite">
            {notice?.kind === 'success' && <FormSuccess>{notice.message}</FormSuccess>}
            {notice?.kind === 'error' && <FormError>{notice.message}</FormError>}
          </div>
          <AsyncContent {...list} skeleton={<SkeletonRows rows={4} />}>
            {(reports) =>
              reports.length === 0 ? (
                <EmptySavedReports canManage={can.canManage} reportsHref={href('/reports')} />
              ) : (
                <SavedReportTable
                  reports={reports}
                  canManage={can.canManage}
                  timeZone={business.timeZone}
                  href={href}
                  onRenamed={(report) => afterChange(`Renamed to “${report.name}”.`)}
                  onDeleted={(report) => afterChange(`Deleted “${report.name}”.`)}
                  onError={(message) => setNotice({ kind: 'error', message })}
                />
              )
            }
          </AsyncContent>
        </div>
      </Panel>
    </>
  )
}

function EmptySavedReports({ canManage, reportsHref }: { canManage: boolean; reportsHref: string }) {
  return (
    <div className="state state-empty saved-empty">
      <p className="saved-empty-title">No saved reports yet</p>
      <p>
        {canManage
          ? 'On the Reports page, pick a report, a store and dates, then choose Save report. Give it a name and choose whether its dates stay fixed or roll forward.'
          : 'Owners and admins save reports from the Reports page. Once they do, you can open them here and export them as CSV or PDF.'}
      </p>
      <Link className="button button-secondary" href={reportsHref}>
        Go to Reports
      </Link>
    </div>
  )
}

/** For an OWNER or ADMIN who hasn't verified their email address yet. */
export function VerifyToManage() {
  return (
    <div className="form-alert saved-verify" role="note">
      <div>
        Verify your email address to save, rename or delete reports. Until then you can open and export them.{' '}
        <ResendVerification />
      </div>
    </div>
  )
}

interface SavedReportTableProps {
  reports: SavedReport[]
  canManage: boolean
  timeZone: string
  href: (path: string) => string
  onRenamed: (report: SavedReport) => void
  onDeleted: (report: SavedReport) => void
  onError: (message: string) => void
}

function SavedReportTable({ reports, canManage, timeZone, href, onRenamed, onDeleted, onError }: SavedReportTableProps) {
  const [busy, setBusy] = useState<number | null>(null)
  const [renaming, setRenaming] = useState<number | null>(null)
  const [confirming, setConfirming] = useState<number | null>(null)

  const remove = async (report: SavedReport) => {
    setBusy(report.id)
    try {
      await savedReportsApi.remove(report.id)
      setConfirming(null)
      onDeleted(report)
    } catch (err) {
      onError(errorMessage(err))
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="table-scroll">
      <table className="data-table saved-table">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col" className="hide-sm">
              Report
            </th>
            <th scope="col" className="hide-sm">
              Dates
            </th>
            <th scope="col" className="hide-sm">
              Store
            </th>
            <th scope="col" className="hide-md">
              Updated
            </th>
            {canManage && (
              <th scope="col" className="num">
                <span className="visually-hidden">Actions</span>
              </th>
            )}
          </tr>
        </thead>
        <tbody>
          {reports.map((report) =>
            renaming === report.id ? (
              <tr key={report.id}>
                {/* Spans every column, including those hidden on small screens. */}
                <td colSpan={6}>
                  <RenameForm
                    report={report}
                    onCancel={() => setRenaming(null)}
                    onRenamed={(renamed) => {
                      setRenaming(null)
                      onRenamed(renamed)
                    }}
                  />
                </td>
              </tr>
            ) : (
              <tr key={report.id}>
                <td>
                  <Link className="cell-link cell-primary break-anywhere" href={href(`/reports/saved/${report.id}`)}>
                    {report.name}
                  </Link>
                  {/* On small screens the hidden columns' facts move under the name. */}
                  <span className="cell-secondary show-sm">
                    {KIND_LABELS[report.kind]} · {storeLabel(report)}
                  </span>
                  <span className="cell-secondary show-sm">{rangeDescription(report)}</span>
                </td>
                <td className="hide-sm nowrap">{KIND_LABELS[report.kind]}</td>
                <td className="hide-sm">
                  <SavedRange report={report} />
                </td>
                <td className="hide-sm">
                  <span className="break-anywhere">{storeLabel(report)}</span>
                </td>
                <td className="hide-md nowrap">
                  <span className="cell-primary">{formatDateTime(report.updatedAt, timeZone)}</span>
                  {report.createdBy && <span className="cell-secondary">by {report.createdBy}</span>}
                </td>
                {canManage && (
                  <td className="num">
                    {confirming === report.id ? (
                      <span className="confirm-inline saved-row-actions" role="group" aria-label={`Delete ${report.name}?`}>
                        <button
                          type="button"
                          className="button button-danger button-small"
                          disabled={busy !== null}
                          onClick={() => void remove(report)}
                        >
                          {busy === report.id ? 'Deleting…' : 'Delete'}
                        </button>
                        <button
                          type="button"
                          className="button button-secondary button-small"
                          disabled={busy !== null}
                          onClick={() => setConfirming(null)}
                        >
                          Cancel
                        </button>
                      </span>
                    ) : (
                      <span className="confirm-inline saved-row-actions">
                        <button
                          type="button"
                          className="button button-secondary button-small"
                          disabled={busy !== null}
                          onClick={() => {
                            setConfirming(null)
                            setRenaming(report.id)
                          }}
                          aria-label={`Rename ${report.name}`}
                        >
                          Rename
                        </button>
                        <button
                          type="button"
                          className="button button-secondary button-small"
                          disabled={busy !== null}
                          onClick={() => {
                            setRenaming(null)
                            setConfirming(report.id)
                          }}
                          aria-label={`Delete ${report.name}`}
                        >
                          Delete
                        </button>
                      </span>
                    )}
                  </td>
                )}
              </tr>
            ),
          )}
        </tbody>
      </table>
    </div>
  )
}

/** "Previous quarter" over "Jul 1 – Sep 30, 2026". */
function SavedRange({ report }: { report: SavedReport }) {
  const [label, dates] = rangeDescription(report).split(' · ', 2)
  return (
    <>
      <span className="cell-primary nowrap">{dates}</span>
      <span className="cell-secondary nowrap">{label}</span>
    </>
  )
}

/** Inline rename: a PUT of the definition with only the name changed. */
function RenameForm({
  report,
  onCancel,
  onRenamed,
}: {
  report: SavedReport
  onCancel: () => void
  onRenamed: (report: SavedReport) => void
}) {
  const [name, setName] = useState(report.name)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const inputId = `rename-${report.id}`

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) {
      setError('Enter a name.')
      return
    }
    if (trimmed === report.name) {
      onCancel()
      return
    }
    setBusy(true)
    setError(null)
    try {
      onRenamed(await savedReportsApi.update(report.id, { ...savedReportInput(report), name: trimmed }))
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
    }
  }

  return (
    <form
      className="saved-rename"
      onSubmit={(e) => void submit(e)}
      onKeyDown={(e) => {
        if (e.key === 'Escape' && !busy) onCancel()
      }}
      noValidate
    >
      <label className="visually-hidden" htmlFor={inputId}>
        New name for {report.name}
      </label>
      <input
        id={inputId}
        className="control saved-rename-input"
        value={name}
        maxLength={SAVED_REPORT_NAME_MAX}
        autoFocus
        autoComplete="off"
        disabled={busy}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${inputId}-error` : undefined}
        onChange={(e) => setName(e.target.value)}
      />
      <span className="confirm-inline">
        <button type="submit" className="button button-primary button-small" disabled={busy} aria-busy={busy}>
          {busy ? 'Saving…' : 'Save'}
        </button>
        <button type="button" className="button button-secondary button-small" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
      </span>
      {error && (
        <p className="form-error saved-rename-error" id={`${inputId}-error`} role="alert">
          {error}
        </p>
      )}
    </form>
  )
}

/** Saved reports in the anonymous public demo: they belong to a business, so sign in first. */
export function SavedReportsSignIn() {
  return (
    <div className="panel page-error">
      <h1 className="page-title">Saved reports need an account</h1>
      <ErrorState message="The demo can show and export reports, but saving them is for members of a business. Sign in or create an account to save your own." />
      <p className="page-error-action">
        <Link className="button button-primary" href={signInHref()}>
          Sign in
        </Link>
      </p>
    </div>
  )
}
