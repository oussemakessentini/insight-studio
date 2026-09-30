import { useState, type FormEvent } from 'react'
import { authApi } from '../../api/account'
import { AuthLayout } from '../../components/AuthLayout'
import { FormError, SubmitButton, TextField } from '../../components/Form'
import { Link } from '../../components/Link'
import { useTouched } from '../../hooks/useTouched'
import { safeNext, signInHref } from '../../lib/router'
import { useLoadedSession } from '../../lib/session'
import { emailError, errorMessage, requiredError } from '../../lib/validation'

export function SignInPage() {
  const { session, reload } = useLoadedSession()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const touched = useTouched<'email' | 'password'>()

  const errors = {
    email: emailError(email),
    password: requiredError(password, 'Enter your password.'),
  }
  const next = safeNext()

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    touched.touchAll()
    if (errors.email || errors.password) return
    setBusy(true)
    setError(null)
    try {
      await authApi.signIn(email.trim(), password)
      await reload(() => next)
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      title="Sign in"
      subtitle="Welcome back to Insight Studio."
      footer={
        <>
          <span>
            New here? <Link href={signInHref(next, '/sign-up')}>Create an account</Link>
          </span>
          {session.demo?.enabled && (
            <span>
              Or <Link href="/">explore the read-only demo</Link>
            </span>
          )}
        </>
      }
    >
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
          onBlur={() => touched.touch('email')}
          error={touched.shows('email') ? errors.email : null}
          disabled={busy}
        />
        <TextField
          label="Password"
          type="password"
          name="password"
          autoComplete="current-password"
          value={password}
          onChange={setPassword}
          onBlur={() => touched.touch('password')}
          error={touched.shows('password') ? errors.password : null}
          disabled={busy}
        />
        <div className="form-row-end">
          <Link className="form-link" href="/forgot-password">
            Forgot your password?
          </Link>
        </div>
        <SubmitButton busy={busy} busyLabel="Signing in…" className="button-block">
          Sign in
        </SubmitButton>
      </form>
    </AuthLayout>
  )
}
