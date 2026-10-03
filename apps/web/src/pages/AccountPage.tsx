import { useState, type FormEvent } from 'react'
import { authApi, membersApi, ROLE_LABELS, type Membership } from '../api/account'
import { FormError, FormSuccess, SubmitButton, TextField } from '../components/Form'
import { Link } from '../components/Link'
import { PageHeader } from '../components/PageHeader'
import { SignOutButton } from '../components/SignOutButton'
import { Panel } from '../components/Panel'
import { ResendVerification } from '../components/VerifyEmailBanner'
import { useTouched } from '../hooks/useTouched'
import { useLoadedSession } from '../lib/session'
import { DeleteAccount, YourData } from '../components/AccountDataPanels'
import { confirmationError, errorMessage, newPasswordError, PASSWORD_HINT, requiredError } from '../lib/validation'
import '../styles/accounts.css'

/** Profile, password, the businesses you belong to, and signing out. */
export function AccountPage() {
  const { session } = useLoadedSession()
  const user = session.user
  if (!user) return null

  return (
    <>
      <PageHeader eyebrow="Account" title={user.displayName} subtitle={user.email} />
      <div className="grid grid-halves account-grid">
        <div className="account-column">
          <Panel title="Profile">
            <dl className="details-list">
              <div>
                <dt>Name</dt>
                <dd>{user.displayName}</dd>
              </div>
              <div>
                <dt>Email</dt>
                <dd className="break-anywhere">{user.email}</dd>
              </div>
              <div>
                <dt>Status</dt>
                <dd>
                  {user.emailVerified ? (
                    'Verified'
                  ) : (
                    <>
                      Not verified yet. <ResendVerification />
                    </>
                  )}
                </dd>
              </div>
            </dl>
          </Panel>
          <YourBusinesses />
          <SignOutPanel />
        </div>
        <div className="account-column">
          <ChangePassword email={user.email} />
          <YourData />
          <DeleteAccount />
        </div>
      </div>
    </>
  )
}

type PasswordField = 'current' | 'password' | 'confirmation'

function ChangePassword({ email }: { email: string }) {
  const { reload } = useLoadedSession()
  const [current, setCurrent] = useState('')
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const touched = useTouched<PasswordField>()

  const errors: Record<PasswordField, string | null> = {
    current: requiredError(current, 'Enter your current password.'),
    password: newPasswordError(password, email),
    confirmation: confirmationError(password, confirmation),
  }

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    touched.touchAll()
    if (Object.values(errors).some(Boolean)) return
    setBusy(true)
    setError(null)
    setSaved(false)
    try {
      await authApi.changePassword(current, password)
      setCurrent('')
      setPassword('')
      setConfirmation('')
      touched.reset()
      setSaved(true)
      // Other sessions were signed out; this one may have a new id.
      void reload().catch(() => undefined)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Panel title="Change password" subtitle="Signs you out on your other devices; this one stays signed in.">
      <form className="form-stack" onSubmit={(e) => void onSubmit(e)} noValidate>
        {/* Lets password managers file the new password under the right account. */}
        <input type="email" name="username" autoComplete="username" value={email} readOnly hidden />
        {error && <FormError>{error}</FormError>}
        {saved && <FormSuccess>Password changed. Your other devices have been signed out.</FormSuccess>}
        <TextField
          label="Current password"
          type="password"
          name="current-password"
          autoComplete="current-password"
          value={current}
          onChange={setCurrent}
          onBlur={(e) => touched.touch('current', e.currentTarget.value)}
          error={touched.shows('current') ? errors.current : null}
          disabled={busy}
        />
        <TextField
          label="New password"
          type="password"
          name="new-password"
          autoComplete="new-password"
          value={password}
          onChange={setPassword}
          onBlur={(e) => touched.touch('password', e.currentTarget.value)}
          error={touched.shows('password') ? errors.password : null}
          hint={PASSWORD_HINT}
          disabled={busy}
        />
        <TextField
          label="Confirm new password"
          type="password"
          name="confirm-password"
          autoComplete="new-password"
          value={confirmation}
          onChange={setConfirmation}
          onBlur={(e) => touched.touch('confirmation', e.currentTarget.value)}
          error={touched.shows('confirmation') ? errors.confirmation : null}
          disabled={busy}
        />
        <div>
          <SubmitButton busy={busy} busyLabel="Saving…">
            Change password
          </SubmitButton>
        </div>
      </form>
    </Panel>
  )
}

function YourBusinesses() {
  const { session, businessId, selectBusiness, reload } = useLoadedSession()
  const [confirming, setConfirming] = useState<number | null>(null)
  const [busy, setBusy] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [left, setLeft] = useState<string | null>(null)
  const userId = session.user?.id

  const leave = async (membership: Membership) => {
    if (userId === undefined) return
    setBusy(membership.businessId)
    setError(null)
    setLeft(null)
    try {
      await membersApi.remove(membership.businessId, userId)
      setConfirming(null)
      setLeft(membership.name)
      // Without the membership the app moves to another business, or to onboarding.
      await reload()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(null)
    }
  }

  return (
    <Panel
      title="Your businesses"
      subtitle="Each business has its own stores, products and sales."
      actions={
        <Link className="panel-link" href="/businesses/new">
          New business
        </Link>
      }
    >
      <div className="form-stack">
        {error && <FormError>{error}</FormError>}
        {left && <FormSuccess>You left {left}.</FormSuccess>}
        {session.memberships.length === 0 ? (
          <p className="text-secondary">You don't belong to a business yet.</p>
        ) : (
          <ul className="membership-list">
            {session.memberships.map((m) => (
              <li key={m.businessId} className="membership-item">
                <div className="membership-main">
                  <span className="cell-primary break-anywhere">{m.name}</span>
                  <span className="membership-meta">
                    <span className={`role-badge role-${m.role.toLowerCase()}`}>{ROLE_LABELS[m.role]}</span>
                    {m.businessId === businessId && <span className="text-muted">Current</span>}
                  </span>
                </div>
                {confirming === m.businessId ? (
                  <div className="membership-actions confirm-inline" role="group" aria-label={`Leave ${m.name}?`}>
                    <span className="confirm-text">Leave {m.name}?</span>
                    <button
                      type="button"
                      className="button button-danger"
                      disabled={busy !== null}
                      onClick={() => void leave(m)}
                    >
                      {busy === m.businessId ? 'Leaving…' : 'Leave'}
                    </button>
                    <button type="button" className="button button-secondary" disabled={busy !== null} onClick={() => setConfirming(null)}>
                      Cancel
                    </button>
                  </div>
                ) : (
                  <div className="membership-actions">
                    {m.businessId !== businessId && (
                      <button type="button" className="button button-secondary" onClick={() => selectBusiness(m.businessId)}>
                        Open
                      </button>
                    )}
                    <button
                      type="button"
                      className="button button-secondary"
                      onClick={() => {
                        setConfirming(m.businessId)
                        setError(null)
                      }}
                    >
                      Leave
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </Panel>
  )
}

function SignOutPanel() {
  return (
    <Panel title="Sign out" subtitle="End your session on this device.">
      <div className="sign-out-row">
        <SignOutButton />
      </div>
    </Panel>
  )
}
