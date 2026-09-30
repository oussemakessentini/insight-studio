import { useState, type FormEvent } from 'react'
import { authApi } from '../../api/account'
import { ApiError } from '../../api/client'
import { AuthLayout } from '../../components/AuthLayout'
import { FormError, FormSuccess, SubmitButton, TextField } from '../../components/Form'
import { Link } from '../../components/Link'
import { useTouched } from '../../hooks/useTouched'
import { useLoadedSession } from '../../lib/session'
import { confirmationError, errorMessage, newPasswordError, PASSWORD_HINT } from '../../lib/validation'

type Field = 'password' | 'confirmation'

export function ResetPasswordPage() {
  const { reload } = useLoadedSession()
  // Read once: the token stays in memory even if the address changes.
  const [token] = useState(() => new URLSearchParams(window.location.search).get('token') ?? '')
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState<{ message: string; invalidLink: boolean } | null>(null)
  const touched = useTouched<Field>()

  const errors: Record<Field, string | null> = {
    password: newPasswordError(password),
    confirmation: confirmationError(password, confirmation),
  }

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    touched.touchAll()
    if (errors.password || errors.confirmation) return
    setBusy(true)
    setError(null)
    try {
      await authApi.resetPassword(token, password)
      setDone(true)
      // A reset signs out every session of the account, possibly this one.
      void reload().catch(() => undefined)
    } catch (err) {
      const invalidLink = err instanceof ApiError && err.status === 400 && /link/i.test(err.message)
      setError({ message: errorMessage(err), invalidLink })
    } finally {
      setBusy(false)
    }
  }

  const footer = (
    <span>
      <Link href="/sign-in">Back to sign in</Link>
    </span>
  )

  if (!token) {
    return (
      <AuthLayout title="Reset your password" footer={footer}>
        <div className="form-stack">
          <FormError>This reset link is invalid or has expired.</FormError>
          <Link className="button button-primary button-block" href="/forgot-password">
            Request a new link
          </Link>
        </div>
      </AuthLayout>
    )
  }

  if (done) {
    return (
      <AuthLayout title="Password changed" footer={footer}>
        <div className="form-stack">
          <FormSuccess>Your password has been reset and every device signed out. Sign in with your new password.</FormSuccess>
          <Link className="button button-primary button-block" href="/sign-in">
            Sign in
          </Link>
        </div>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title="Choose a new password" subtitle="This signs you out on every device." footer={footer}>
      <form className="form-stack" onSubmit={(e) => void onSubmit(e)} noValidate>
        {error && (
          <FormError>
            {error.message}
            {error.invalidLink && (
              <>
                {' '}
                <Link href="/forgot-password">Request a new link</Link>.
              </>
            )}
          </FormError>
        )}
        <TextField
          label="New password"
          type="password"
          name="new-password"
          autoComplete="new-password"
          autoFocus
          value={password}
          onChange={setPassword}
          onBlur={() => touched.touch('password')}
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
          onBlur={() => touched.touch('confirmation')}
          error={touched.shows('confirmation') ? errors.confirmation : null}
          disabled={busy}
        />
        <SubmitButton busy={busy} busyLabel="Saving…" className="button-block">
          Set new password
        </SubmitButton>
      </form>
    </AuthLayout>
  )
}
