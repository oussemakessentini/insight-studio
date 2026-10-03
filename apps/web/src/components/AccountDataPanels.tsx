import { useState, type FormEvent, type MouseEvent } from 'react'
import { ROLE_LABELS } from '../api/account'
import { accountDataApi } from '../api/accountManagement'
import { useApi } from '../hooks/useApi'
import { emailConfirmed } from '../lib/accountSettings'
import { plural } from '../lib/audit'
import { useLoadedSession } from '../lib/session'
import { errorMessage } from '../lib/validation'
import '../styles/reports.css'
import '../styles/settings.css'
import { Dialog } from './Dialog'
import { FormError, FormSuccess, TextField } from './Form'
import { DownloadIcon } from './Icons'
import { ErrorState, Panel, SkeletonRows } from './Panel'

/** Account page: "Download your data" (contract §3, account export). */
export function YourData() {
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; message: string } | null>(null)

  const download = async () => {
    setBusy(true)
    setNotice(null)
    try {
      await accountDataApi.exportData()
      setNotice({ kind: 'success', message: 'Your data has been downloaded.' })
    } catch (err) {
      setNotice({ kind: 'error', message: errorMessage(err) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Panel
      title="Your data"
      subtitle="A JSON file with your profile, memberships, what you created and the changes you made. Up to 5 per hour."
    >
      <div className="form-stack">
        <div aria-live="polite">
          {notice?.kind === 'success' && <FormSuccess>{notice.message}</FormSuccess>}
          {notice?.kind === 'error' && <FormError>{notice.message}</FormError>}
        </div>
        <div>
          <button
            type="button"
            className="button button-secondary button-with-icon"
            disabled={busy}
            aria-busy={busy}
            onClick={() => void download()}
          >
            <DownloadIcon width={16} height={16} />
            {busy ? 'Preparing download…' : 'Download your data'}
          </button>
        </div>
      </div>
    </Panel>
  )
}

/** Account page: "Delete account" with its preview, last-owner protection and typed confirmation (§4). */
export function DeleteAccount() {
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  return (
    <Panel title="Delete account" subtitle="Removes your account and your access to every business." className="danger-zone">
      <div className="form-stack">
        <p className="text-secondary">
          Charts, dashboards, imports and reports you created stay with their businesses and show “Deleted account”. This
          can't be undone.
        </p>
        <div>
          <button type="button" className="button button-danger" onClick={() => setOpen(true)}>
            Delete account
          </button>
        </div>
      </div>
      <Dialog open={open} title="Delete your account?" onClose={() => setOpen(false)} busy={busy}>
        <DeleteAccountForm onClose={() => setOpen(false)} onBusyChange={setBusy} />
      </Dialog>
    </Panel>
  )
}

function DeleteAccountForm({ onClose, onBusyChange }: { onClose: () => void; onBusyChange: (busy: boolean) => void }) {
  const { reload, selectBusiness } = useLoadedSession()
  const preview = useApi('account-deletion-preview', (signal) => accountDataApi.deletionPreview(signal))
  const [password, setPassword] = useState('')
  const [confirmEmail, setConfirmEmail] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (preview.error) return <ErrorState message={preview.error.message} onRetry={preview.retry} />
  if (!preview.data) return <SkeletonRows rows={4} />
  const { account, memberships, blockingBusinesses, authoredContent, openInvitationsSent } = preview.data

  if (blockingBusinesses.length > 0) {
    const openMembers = (businessId: number) => (event: MouseEvent<HTMLAnchorElement>) => {
      if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
      event.preventDefault()
      onClose()
      selectBusiness(businessId, '/settings/members')
    }
    return (
      <div className="form-stack">
        <div className="form-alert form-alert-error" role="alert">
          <div>
            You are the only owner of{' '}
            {blockingBusinesses.length === 1 ? 'this business' : `these ${blockingBusinesses.length} businesses`}, so your
            account can't be deleted yet. Make another member an owner, or delete the business first.
          </div>
        </div>
        <ul className="blocking-list" aria-label="Businesses you are the only owner of">
          {blockingBusinesses.map((b) => (
            <li key={b.businessId}>
              <span className="cell-primary break-anywhere">{b.businessName}</span>
              <a className="form-link" href="/settings/members" onClick={openMembers(b.businessId)}>
                Manage members of {b.businessName}
              </a>
            </li>
          ))}
        </ul>
        <div className="form-actions dialog-actions">
          <button type="button" className="button button-secondary" onClick={onClose}>
            Close
          </button>
          <button type="button" className="button button-danger" disabled>
            Delete account
          </button>
        </div>
      </div>
    )
  }

  const confirmed = emailConfirmed(confirmEmail, account.email)
  const authored = [
    authoredContent.charts > 0 && plural(authoredContent.charts, 'chart'),
    authoredContent.dashboards > 0 && plural(authoredContent.dashboards, 'dashboard'),
    authoredContent.savedReports > 0 && plural(authoredContent.savedReports, 'saved report'),
    authoredContent.imports > 0 && plural(authoredContent.imports, 'import'),
  ].filter(Boolean)

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (!confirmed || !password) return
    setBusy(true)
    onBusyChange(true)
    setError(null)
    try {
      await accountDataApi.delete(password, confirmEmail.trim())
      // Every session of the account ended; the sign-in page says what happened.
      await reload(() => '/sign-in?accountDeleted=1')
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
      onBusyChange(false)
    }
  }

  return (
    <form className="form-stack" onSubmit={(e) => void onSubmit(e)} noValidate>
      <p className="dialog-text">
        This permanently deletes <strong className="break-anywhere">{account.email}</strong> and signs you out everywhere. You
        can sign up again later with the same address, as a new account.
      </p>
      {memberships.length > 0 && (
        <div>
          <p className="dialog-text">You will leave:</p>
          <ul className="deletion-member-list">
            {memberships.map((m) => (
              <li key={m.businessId}>
                <span className="break-anywhere">{m.businessName}</span>{' '}
                <span className={`role-badge role-${m.role.toLowerCase()}`}>{ROLE_LABELS[m.role]}</span>{' '}
                <span className="text-muted">{plural(m.memberCount, 'member')}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {authored.length > 0 && (
        <p className="dialog-text">
          {authored.join(', ')} you created stay with their businesses, shown as by “Deleted account”.
        </p>
      )}
      {openInvitationsSent > 0 && (
        <p className="dialog-text">
          {plural(openInvitationsSent, 'open invitation')} you sent will be revoked.
        </p>
      )}
      {error && <FormError>{error}</FormError>}
      <TextField
        label="Your password"
        type="password"
        name="current-password"
        autoComplete="current-password"
        value={password}
        onChange={setPassword}
        disabled={busy}
        autoFocus
      />
      <TextField
        label="Type your email to confirm"
        type="email"
        value={confirmEmail}
        onChange={setConfirmEmail}
        hint={
          <>
            Type <strong className="break-anywhere">{account.email}</strong>.
          </>
        }
        autoComplete="off"
        spellCheck={false}
        disabled={busy}
      />
      <div className="form-actions dialog-actions">
        <button type="button" className="button button-secondary" onClick={onClose} disabled={busy}>
          Cancel
        </button>
        <button type="submit" className="button button-danger" disabled={busy || !confirmed || !password} aria-busy={busy}>
          {busy ? 'Deleting…' : 'Delete account'}
        </button>
      </div>
    </form>
  )
}
