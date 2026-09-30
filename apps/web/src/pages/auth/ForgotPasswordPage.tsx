import { useState, type FormEvent } from 'react'
import { authApi } from '../../api/account'
import { ApiError } from '../../api/client'
import { AuthLayout } from '../../components/AuthLayout'
import { FormError, FormSuccess, SubmitButton, TextField } from '../../components/Form'
import { Link } from '../../components/Link'
import { useTouched } from '../../hooks/useTouched'
import { emailError, errorMessage } from '../../lib/validation'

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const [busy, setBusy] = useState(false)
  const [sentTo, setSentTo] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const touched = useTouched<'email'>()
  const fieldError = emailError(email)

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    touched.touchAll()
    if (fieldError) return
    setBusy(true)
    setError(null)
    try {
      await authApi.forgotPassword(email.trim())
      setSentTo(email.trim())
    } catch (err) {
      // The answer never reveals whether an account exists: only a request that didn't get through
      // (network, rate limit, server error) is reported; anything else gets the neutral message.
      if (err instanceof ApiError && err.status !== 0 && err.status !== 429 && err.status < 500) setSentTo(email.trim())
      else setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      title="Reset your password"
      subtitle="Enter your account's email and we'll send you a link to choose a new password."
      footer={
        <span>
          Remembered it? <Link href="/sign-in">Back to sign in</Link>
        </span>
      }
    >
      {sentTo ? (
        <div className="form-stack">
          <FormSuccess>
            <strong>Check your inbox.</strong> If an account exists for {sentTo}, we sent a link to reset its password. The
            link works once and expires after 30 minutes.
          </FormSuccess>
          <button
            type="button"
            className="button button-secondary button-block"
            onClick={() => {
              setSentTo(null)
              touched.reset()
            }}
          >
            Use another email
          </button>
        </div>
      ) : (
        <form className="form-stack" onSubmit={(e) => void onSubmit(e)} noValidate>
          {error && <FormError>{error}</FormError>}
          <TextField
            label="Email"
            type="email"
            name="email"
            autoComplete="email"
            inputMode="email"
            spellCheck={false}
            autoFocus
            value={email}
            onChange={setEmail}
            onBlur={(e) => touched.touch('email', e.currentTarget.value)}
            error={touched.shows('email') ? fieldError : null}
            disabled={busy}
          />
          <SubmitButton busy={busy} busyLabel="Sending…" className="button-block">
            Send reset link
          </SubmitButton>
        </form>
      )}
    </AuthLayout>
  )
}
