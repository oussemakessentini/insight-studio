import { useState } from 'react'
import type { ReportKind } from '../../api/reports'
import { savedReportsApi, type RelativePreset, type SavedReport } from '../../api/savedReports'
import type { StoreOption } from '../../api/types'
import type { Filters, RangePreset } from '../../lib/filters'
import { KIND_LABELS, rangeDescription } from '../../lib/savedReports'
import { Dialog } from '../Dialog'
import { FormSuccess } from '../Form'
import { Link } from '../Link'
import { ResendVerification } from '../VerifyEmailBanner'
import { SavedReportForm } from './SavedReportForm'

/** The rolling period closest to the page's date filter, preselected when choosing one. */
const SUGGESTED_PRESET: Partial<Record<RangePreset, RelativePreset>> = {
  '7d': 'last_7_days',
  '30d': 'last_30_days',
  '90d': 'last_90_days',
}

interface SaveReportDialogProps {
  open: boolean
  onClose: () => void
  kind: ReportKind
  filters: Filters
  stores: StoreOption[]
  /** An OWNER or ADMIN whose email address isn't verified yet: explain instead of offering the form. */
  needsVerification: boolean
  /** Builds an in-app link that carries the current filters. */
  href: (path: string) => string
}

/** "Save report" on the Reports page: names the report on screen and chooses how its dates move. */
export function SaveReportDialog({ open, onClose, kind, filters, stores, needsVerification, href }: SaveReportDialogProps) {
  const [saved, setSaved] = useState<SavedReport | null>(null)
  const close = () => {
    setSaved(null)
    onClose()
  }

  return (
    <Dialog open={open} title={saved ? 'Report saved' : 'Save report'} onClose={close}>
      {needsVerification ? (
        <div className="form-stack">
          <p className="dialog-text">
            Verify your email address to save reports. Until then you can run and export reports, but not save or
            change them. Open the link we emailed you.
          </p>
          <div className="form-actions dialog-actions">
            <button type="button" className="button button-secondary" onClick={close}>
              Close
            </button>
            <ResendVerification variant="button" />
          </div>
        </div>
      ) : saved ? (
        <div className="form-stack">
          <FormSuccess>
            <strong className="break-anywhere">{saved.name}</strong> is saved. Today it covers{' '}
            <strong>{rangeDescription(saved)}</strong>.
          </FormSuccess>
          <p className="dialog-text">
            Anyone in this business can open it from Saved reports and export it as CSV or PDF.
            {saved.range.type === 'relative' && ' Its dates move forward each time it runs.'}
          </p>
          <div className="form-actions dialog-actions">
            <Link className="button button-secondary" href={href('/reports/saved')} onClick={close}>
              All saved reports
            </Link>
            <Link className="button button-primary" href={href(`/reports/saved/${saved.id}`)} onClick={close}>
              Open saved report
            </Link>
          </div>
        </div>
      ) : (
        <SavedReportForm
          mode="create"
          initial={{
            name: '',
            kind,
            storeId: filters.storeId,
            range: { type: 'fixed', from: filters.from, to: filters.to },
          }}
          stores={stores}
          suggestedPreset={SUGGESTED_PRESET[filters.preset] ?? 'last_30_days'}
          submitLabel={`Save ${KIND_LABELS[kind].toLowerCase()}`}
          busyLabel="Saving…"
          onSubmit={async (input) => setSaved(await savedReportsApi.create(input))}
          onCancel={close}
        />
      )}
    </Dialog>
  )
}
