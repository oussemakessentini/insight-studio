import { useState, type FormEvent, type ReactNode } from 'react'
import { ApiError } from '../../api/client'
import { planLimitOf } from '../../lib/billing'
import { DASHBOARD_NAME_MAX, dashboardNameError } from '../../lib/dashboards'
import { errorMessage } from '../../lib/validation'
import { Dialog } from '../Dialog'
import { FormError, SubmitButton, TextField } from '../Form'

interface DashboardNameDialogProps {
  open: boolean
  title: string
  initialName: string
  submitLabel: string
  busyLabel: string
  /** Shown above the field, e.g. what else the save changes. */
  note?: ReactNode
  /** Rejects with the error to show (a taken name is a 409, shown next to the field). */
  onSubmit: (name: string) => Promise<void>
  onClose: () => void
}

/** Names a new dashboard or renames one. The form mounts only while open, so it starts fresh. */
export function DashboardNameDialog({ open, title, onClose, ...form }: DashboardNameDialogProps) {
  const [busy, setBusy] = useState(false)
  return (
    <Dialog open={open} title={title} onClose={onClose} busy={busy}>
      <NameForm {...form} onClose={onClose} onBusyChange={setBusy} />
    </Dialog>
  )
}

function NameForm({
  initialName,
  submitLabel,
  busyLabel,
  note,
  onSubmit,
  onClose,
  onBusyChange,
}: Omit<DashboardNameDialogProps, 'open' | 'title'> & { onBusyChange: (busy: boolean) => void }) {
  const [name, setName] = useState(initialName)
  const [touched, setTouched] = useState(false)
  const [busy, setBusy] = useState(false)
  const [nameTaken, setNameTaken] = useState<string | null>(null)
  // The caught error (not just its text), so a plan-limit refusal can link to the plans.
  const [error, setError] = useState<unknown>(null)
  const nameError = dashboardNameError(name)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setTouched(true)
    if (nameError) return
    setBusy(true)
    onBusyChange(true)
    setError(null)
    setNameTaken(null)
    try {
      await onSubmit(name.trim())
    } catch (err) {
      if (err instanceof ApiError && err.status === 409 && !planLimitOf(err) && /name/i.test(err.message)) setNameTaken(err.message)
      else setError(err)
    } finally {
      setBusy(false)
      onBusyChange(false)
    }
  }

  return (
    <form className="form-stack" onSubmit={(e) => void submit(e)} noValidate>
      {note && <p className="dialog-text">{note}</p>}
      {error != null && <FormError error={error}>{errorMessage(error)}</FormError>}
      <TextField
        label="Name"
        value={name}
        onChange={(value) => {
          setName(value)
          setNameTaken(null)
        }}
        onBlur={() => setTouched(true)}
        error={nameTaken ?? (touched ? nameError : null)}
        hint={`Up to ${DASHBOARD_NAME_MAX} characters, unique in this business.`}
        maxLength={DASHBOARD_NAME_MAX + 20}
        autoComplete="off"
        autoFocus
      />
      <div className="form-actions dialog-actions">
        <button type="button" className="button button-secondary" onClick={onClose} disabled={busy}>
          Cancel
        </button>
        <SubmitButton busy={busy} busyLabel={busyLabel}>
          {submitLabel}
        </SubmitButton>
      </div>
    </form>
  )
}
