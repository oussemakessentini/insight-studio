import { useEffect, useState } from 'react'
import { authApi, invitationsApi, ROLE_LABELS, type InvitationPreview } from '../../api/account'
import { ApiError } from '../../api/client'
import { AuthLayout } from '../../components/AuthLayout'
import { FormError } from '../../components/Form'
import { Link } from '../../components/Link'
import { signInHref } from '../../lib/router'
import { useLoadedSession } from '../../lib/session'
import { errorMessage } from '../../lib/validation'

const STORAGE_KEY = 'insight-studio.invitation'
const HERE = '/invite'

/**
 * The invitation token, read once. It moves from the address bar to this tab's session storage
 * (so it survives signing in or creating an account on the way, but never lands in history,
 * bookmarks or a shared screenshot of the URL).
 */
function takeToken(): string {
  const fromUrl = new URLSearchParams(window.location.search).get('token')
  try {
    if (fromUrl) {
      window.sessionStorage.setItem(STORAGE_KEY, fromUrl)
      window.history.replaceState(window.history.state, '', HERE)
      return fromUrl
    }
    return window.sessionStorage.getItem(STORAGE_KEY) ?? ''
  } catch {
    return fromUrl ?? '' // Storage blocked: keep it in memory only.
  }
}

function forgetToken() {
  try {
    window.sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // Nothing stored.
  }
}

type Preview = { status: 'loading' } | { status: 'ready'; invitation: InvitationPreview } | { status: 'error'; message: string }

export function InvitePage() {
  const { session, reload, selectBusiness } = useLoadedSession()
  const [token] = useState(takeToken)
  const [preview, setPreview] = useState<Preview>(token ? { status: 'loading' } : { status: 'error', message: '' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!token) return
    let active = true
    invitationsApi
      .preview(token)
      .then((invitation) => active && setPreview({ status: 'ready', invitation }))
      .catch((err: unknown) => active && setPreview({ status: 'error', message: errorMessage(err) }))
    return () => {
      active = false
    }
  }, [token])

  const accept = async () => {
    setBusy(true)
    setError(null)
    try {
      const joined = await invitationsApi.accept(token)
      forgetToken()
      selectBusiness(joined.businessId, null)
      await reload(() => '/')
    } catch (err) {
      setError(errorMessage(err))
      // A spent or expired link won't work on a retry either.
      if (err instanceof ApiError && err.status === 400) forgetToken()
    } finally {
      setBusy(false)
    }
  }

  const signOut = async () => {
    setBusy(true)
    try {
      await authApi.signOut()
      await reload(() => signInHref(HERE))
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const footer = (
    <span>
      <Link href="/">Go to Insight Studio</Link>
    </span>
  )

  if (preview.status === 'loading') {
    return (
      <AuthLayout title="Invitation" footer={footer}>
        <p className="auth-subtitle" role="status">
          Checking your invitation…
        </p>
      </AuthLayout>
    )
  }

  if (preview.status === 'error') {
    return (
      <AuthLayout title="Invitation" footer={footer}>
        <div className="form-stack">
          <FormError>{preview.message || 'This invitation is invalid or has expired.'}</FormError>
          <p className="form-hint">Invitations work once and expire after 7 days. Ask the person who invited you to send a new one.</p>
        </div>
      </AuthLayout>
    )
  }

  const { invitation } = preview
  const role = ROLE_LABELS[invitation.role].toLowerCase()
  const expires = formatExpiry(invitation.expiresAt)
  const signedInAs = session.authenticated ? session.user?.email ?? '' : null
  const matches = signedInAs !== null && signedInAs.toLowerCase() === invitation.email.toLowerCase()

  return (
    <AuthLayout
      title={`Join ${invitation.businessName}`}
      subtitle={
        <>
          {invitation.invitedBy} invited <strong className="break-anywhere">{invitation.email}</strong> to join as {role}.
          {expires && <> The invitation expires {expires}.</>}
        </>
      }
      footer={footer}
    >
      <div className="form-stack" aria-live="polite">
        {error && <FormError>{error}</FormError>}

        {signedInAs === null && (
          <>
            <p className="form-hint">Sign in or create an account with {invitation.email} to accept.</p>
            <Link className="button button-primary button-block" href={signInHref(HERE)}>
              Sign in to accept
            </Link>
            <Link className="button button-secondary button-block" href={signInHref(HERE, '/sign-up')}>
              Create an account
            </Link>
          </>
        )}

        {signedInAs !== null && matches && (
          <button type="button" className="button button-primary button-block" disabled={busy} onClick={() => void accept()}>
            {busy ? 'Joining…' : `Join ${invitation.businessName}`}
          </button>
        )}

        {signedInAs !== null && !matches && (
          <>
            <p className="form-hint">
              You're signed in as <strong className="break-anywhere">{signedInAs}</strong>. This invitation is for{' '}
              <strong className="break-anywhere">{invitation.email}</strong>: sign out, then sign in or create an account
              with that address.
            </p>
            <button type="button" className="button button-secondary button-block" disabled={busy} onClick={() => void signOut()}>
              {busy ? 'Signing out…' : 'Sign out and continue'}
            </button>
          </>
        )}
      </div>
    </AuthLayout>
  )
}

function formatExpiry(instant: string): string | null {
  const date = new Date(instant)
  if (Number.isNaN(date.getTime())) return null
  return `on ${new Intl.DateTimeFormat('en', { dateStyle: 'medium', timeStyle: 'short' }).format(date)}`
}
