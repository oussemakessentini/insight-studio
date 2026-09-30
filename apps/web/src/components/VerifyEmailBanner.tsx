import { useState } from 'react'
import { authApi } from '../api/account'
import { useSession } from '../lib/session'
import { errorMessage } from '../lib/validation'
import { MailIcon } from './Icons'

type State = { status: 'idle' | 'sending' | 'sent' } | { status: 'error'; message: string }

/** Sends a new verification link; shows what happened next to the button. */
export function ResendVerification({ variant = 'link' }: { variant?: 'link' | 'button' }) {
  const [state, setState] = useState<State>({ status: 'idle' })

  const resend = async () => {
    setState({ status: 'sending' })
    try {
      await authApi.resendVerification()
      setState({ status: 'sent' })
    } catch (err) {
      setState({ status: 'error', message: errorMessage(err) })
    }
  }

  return (
    <span aria-live="polite">
      {state.status === 'sent' ? (
        <span>New link sent. Check your inbox.</span>
      ) : (
        <button
          type="button"
          className={variant === 'button' ? 'button button-secondary' : 'link-button'}
          disabled={state.status === 'sending'} onClick={() => void resend()}>
          {state.status === 'sending' ? 'Sending…' : 'Send a new link'}
        </button>
      )}
      {state.status === 'error' && <span className="verify-banner-error"> {state.message}</span>}
    </span>
  )
}

/**
 * Shown to a signed-in account that hasn't verified its email address: it can look around but not
 * create or change a business (the API refuses those anyway).
 */
export function VerifyEmailBanner() {
  const loaded = useSession()
  const user = loaded?.session.authenticated ? loaded.session.user : null
  if (!user || user.emailVerified) return null
  return (
    <div className="demo-banner verify-banner" role="note" aria-label="Email address not verified">
      <MailIcon width={16} height={16} />
      <p>
        Verify <strong className="break-anywhere">{user.email}</strong> to create or change a business: open the link we
        emailed you. <ResendVerification />
      </p>
    </div>
  )
}
