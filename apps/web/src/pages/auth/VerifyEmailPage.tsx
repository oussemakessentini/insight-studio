import { useEffect, useState } from 'react'
import { authApi } from '../../api/account'
import { AuthLayout } from '../../components/AuthLayout'
import { FormError, FormSuccess } from '../../components/Form'
import { Link } from '../../components/Link'
import { ResendVerification } from '../../components/VerifyEmailBanner'
import { signInHref } from '../../lib/router'
import { useLoadedSession } from '../../lib/session'
import { errorMessage } from '../../lib/validation'

// The token from the link. Moved out of the address bar at once (only /verify-email stays in
// history); kept here so a second read (StrictMode runs state initializers twice) still finds it.
let linkToken = ''

function takeToken(): string {
  const fromUrl = new URLSearchParams(window.location.search).get('token')
  if (fromUrl) {
    linkToken = fromUrl
    window.history.replaceState(window.history.state, '', '/verify-email')
  }
  return linkToken
}

// A token works once: every render and effect run (StrictMode mounts twice in development) shares
// the one request made for it.
const verifications = new Map<string, Promise<void>>()

function verifyOnce(token: string): Promise<void> {
  let request = verifications.get(token)
  if (!request) {
    request = authApi.verifyEmail(token)
    verifications.set(token, request)
  }
  return request
}

type State = { status: 'verifying' } | { status: 'verified' } | { status: 'error'; message: string }

/**
 * Opened from the verification email. Works signed in or out, in any browser: the link verifies the
 * account it was sent to, and signs no one in.
 */
export function VerifyEmailPage() {
  const { session, reload } = useLoadedSession()
  const [token] = useState(takeToken)
  const [state, setState] = useState<State>(
    token ? { status: 'verifying' } : { status: 'error', message: 'This verification link is invalid or has expired.' },
  )

  useEffect(() => {
    if (!token) return
    let active = true
    verifyOnce(token)
      .then(() => {
        if (!active) return
        setState({ status: 'verified' })
        // If this browser is signed in (as that account), it can now create a business.
        void reload().catch(() => undefined)
      })
      .catch((err: unknown) => active && setState({ status: 'error', message: errorMessage(err) }))
    return () => {
      active = false
    }
    // reload is stable; the token never changes.
  }, [token, reload])

  const signedIn = session.authenticated
  const footer = (
    <span>
      <Link href="/">Go to Insight Studio</Link>
    </span>
  )

  if (state.status === 'verifying') {
    return (
      <AuthLayout title="Verifying your email" footer={footer}>
        <p className="auth-subtitle" role="status">
          One moment…
        </p>
      </AuthLayout>
    )
  }

  if (state.status === 'verified') {
    return (
      <AuthLayout title="Email verified" footer={footer}>
        <div className="form-stack">
          <FormSuccess>Your email address is verified. You can now create a business or join one.</FormSuccess>
          {signedIn ? (
            <Link className="button button-primary button-block" href="/">
              Continue
            </Link>
          ) : (
            <Link className="button button-primary button-block" href={signInHref('/')}>
              Sign in
            </Link>
          )}
        </div>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title="Verify your email" footer={footer}>
      <div className="form-stack">
        <FormError>{state.message}</FormError>
        <p className="form-hint">
          Links work once and expire after 24 hours.{' '}
          {signedIn && !session.user?.emailVerified ? (
            <ResendVerification />
          ) : (
            <>
              <Link href={signInHref('/account')}>Sign in</Link> to send a new one.
            </>
          )}
        </p>
      </div>
    </AuthLayout>
  )
}
