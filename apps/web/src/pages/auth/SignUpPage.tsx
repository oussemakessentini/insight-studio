import { useState, type FormEvent } from 'react'
import { authApi } from '../../api/account'
import { ApiError } from '../../api/client'
import { AuthLayout } from '../../components/AuthLayout'
import { FormError, SubmitButton, TextField } from '../../components/Form'
import { Link } from '../../components/Link'
import { useTouched } from '../../hooks/useTouched'
import { safeNext, signInHref } from '../../lib/router'
import { useLoadedSession } from '../../lib/session'
import { emailError, errorMessage, newPasswordError, PASSWORD_HINT, requiredError } from '../../lib/validation'

type Field = 'displayName' | 'email' | 'password'

export function SignUpPage() {
  const { session, reload } = useLoadedSession()
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<{ message: string; emailTaken: boolean } | null>(null)
  const touched = useTouched<Field>()

  const errors: Record<Field, string | null> = {
    displayName: requiredError(displayName, 'Enter your name.'),
    email: emailError(email),
    password: newPasswordError(password, email),
  }
  const next = safeNext()

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    touched.touchAll()
    if (Object.values(errors).some(Boolean)) return
    setBusy(true)
    setError(null)
    try {
      await authApi.signUp(email.trim(), password, displayName.trim())
      // A new account has no business yet: the app continues with onboarding.
      await reload(() => (next === '/' ? '/businesses/new' : next))
    } catch (err) {
      setError({ message: errorMessage(err), emailTaken: err instanceof ApiError && err.status === 409 })
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      title="Create your account"
      subtitle="Then set up your business, or join one when a teammate adds you."
      footer={
        <>
          <span>
            Already have an account? <Link href={signInHref(next)}>Sign in</Link>
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
        {error && (
          <FormError>
            {error.message}
            {error.emailTaken && (
              <>
                {' '}
                <Link href={signInHref(next)}>Sign in</Link> or <Link href="/forgot-password">reset your password</Link>.
              </>
            )}
          </FormError>
        )}
        <TextField
          label="Your name"
          name="name"
          autoComplete="name"
          autoFocus
          maxLength={100}
          value={displayName}
          onChange={setDisplayName}
          onBlur={(e) => touched.touch('displayName', e.currentTarget.value)}
          error={touched.shows('displayName') ? errors.displayName : null}
          disabled={busy}
        />
        <TextField
          label="Email"
          type="email"
          name="email"
          autoComplete="email"
          inputMode="email"
          spellCheck={false}
          value={email}
          onChange={setEmail}
          onBlur={(e) => touched.touch('email', e.currentTarget.value)}
          error={touched.shows('email') ? errors.email : null}
          disabled={busy}
        />
        <TextField
          label="Password"
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
        <SubmitButton busy={busy} busyLabel="Creating account…" className="button-block">
          Create account
        </SubmitButton>
      </form>
    </AuthLayout>
  )
}
