import { useState } from 'react'
import { authApi } from '../api/account'
import { useLoadedSession } from '../lib/session'
import { errorMessage } from '../lib/validation'

/** Ends the session, then shows the public demo (when enabled) or the sign-in page. */
export function SignOutButton({ className = 'button button-secondary' }: { className?: string }) {
  const { reload } = useLoadedSession()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const signOut = async () => {
    setBusy(true)
    setError(null)
    try {
      await authApi.signOut()
      await reload((session) => (session.demo?.enabled ? '/' : '/sign-in'))
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
    }
  }

  return (
    <>
      <button type="button" className={className} disabled={busy} onClick={() => void signOut()}>
        {busy ? 'Signing out…' : 'Sign out'}
      </button>
      {error && (
        <span className="form-error" role="alert">
          {error}
        </span>
      )}
    </>
  )
}
