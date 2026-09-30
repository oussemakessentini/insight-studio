import { useMemo, useState, type FormEvent } from 'react'
import { businessesApi } from '../api/account'
import { FormError, SelectField, SubmitButton, TextField } from '../components/Form'
import { Link } from '../components/Link'
import { useTouched } from '../hooks/useTouched'
import { useLoadedSession } from '../lib/session'
import { errorMessage, requiredError } from '../lib/validation'
import '../styles/accounts.css'

const FALLBACK_CURRENCIES = ['USD', 'EUR', 'GBP', 'CAD', 'AUD', 'CHF', 'JPY', 'TND', 'MAD']

function currencyOptions(): { code: string; label: string }[] {
  let codes: string[]
  try {
    codes = Intl.supportedValuesOf('currency')
  } catch {
    codes = FALLBACK_CURRENCIES
  }
  let names: Intl.DisplayNames | null = null
  try {
    names = new Intl.DisplayNames(['en'], { type: 'currency' })
  } catch {
    // Codes only.
  }
  return codes.map((code) => {
    const name = names?.of(code)
    return { code, label: name && name !== code ? `${code} · ${name}` : code }
  })
}

function localTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

function timeZoneOptions(local: string): string[] {
  let zones: string[]
  try {
    zones = Intl.supportedValuesOf('timeZone')
  } catch {
    zones = []
  }
  const all = new Set([...zones, local, 'UTC'])
  return [...all].sort()
}

/**
 * Creates a business owned by the signed-in user, then opens its catalog setup. Doubles as the
 * onboarding step for a new account (`onboarding`).
 */
export function NewBusinessPage({ onboarding = false }: { onboarding?: boolean }) {
  const { reload, selectBusiness } = useLoadedSession()
  const local = useMemo(() => localTimeZone(), [])
  const currencies = useMemo(() => currencyOptions(), [])
  const zones = useMemo(() => timeZoneOptions(local), [local])
  const [name, setName] = useState('')
  const [currency, setCurrency] = useState('USD')
  const [timeZone, setTimeZone] = useState(local)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const touched = useTouched<'name'>()
  const nameError = requiredError(name, 'Enter the business name.')

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    touched.touchAll()
    if (nameError) return
    setBusy(true)
    setError(null)
    try {
      const created = await businessesApi.create(name.trim(), currency, timeZone)
      // Remember the choice now; it takes effect once the reloaded session has the membership.
      selectBusiness(created.businessId, null)
      // A new business starts empty, so continue with adding stores and products.
      await reload(() => '/settings/catalog?created=1')
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
    }
  }

  return (
    <div className="panel new-business">
      <div className="new-business-head">
        <h1 className="page-title">{onboarding ? 'Set up your business' : 'Create a business'}</h1>
        <p className="page-subtitle">
          {onboarding
            ? "You'll be its owner. You can add stores, products and teammates next. If a teammate is adding you to their business instead, you'll see it here once they do."
            : "You'll be its owner. Its stores, products and sales are kept separate from your other businesses."}
        </p>
      </div>
      <form className="form-stack" onSubmit={(e) => void onSubmit(e)} noValidate>
        {error && <FormError>{error}</FormError>}
        <TextField
          label="Business name"
          name="organization"
          autoComplete="organization"
          autoFocus
          maxLength={100}
          value={name}
          onChange={setName}
          onBlur={(e) => touched.touch('name', e.currentTarget.value)}
          error={touched.shows('name') ? nameError : null}
          disabled={busy}
        />
        <div className="form-columns">
          <SelectField
            label="Currency"
            value={currency}
            onChange={setCurrency}
            hint="Used for all amounts."
            disabled={busy}
          >
            {currencies.map((c) => (
              <option key={c.code} value={c.code}>
                {c.label}
              </option>
            ))}
          </SelectField>
          <SelectField
            label="Time zone"
            value={timeZone}
            onChange={setTimeZone}
            hint="Days and months in reports follow it."
            disabled={busy}
          >
            {zones.map((zone) => (
              <option key={zone} value={zone}>
                {zone.replaceAll('_', ' ')}
              </option>
            ))}
          </SelectField>
        </div>
        <div className="form-actions">
          <SubmitButton busy={busy} busyLabel="Creating…">
            Create business
          </SubmitButton>
          {!onboarding && (
            <Link className="button button-secondary" href="/">
              Cancel
            </Link>
          )}
        </div>
      </form>
    </div>
  )
}
