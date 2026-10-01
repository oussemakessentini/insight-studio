import { useId, useState, type FormEvent } from 'react'
import type { ReportKind } from '../../api/reports'
import {
  MAX_RANGE_DAYS,
  RELATIVE_PRESETS,
  SAVED_REPORT_NAME_MAX,
  type RelativePreset,
  type SavedReportInput,
} from '../../api/savedReports'
import type { DateRange, StoreOption } from '../../api/types'
import { useTouched } from '../../hooks/useTouched'
import { daysBetweenInclusive, formatDateRange } from '../../lib/format'
import { KIND_LABELS } from '../../lib/savedReports'
import { errorMessage } from '../../lib/validation'
import { FormError, SelectField, SubmitButton, TextField } from '../Form'
import { REPORT_TABS } from './tabs'

interface SavedReportFormProps {
  initial: SavedReportInput
  /**
   * `create` saves what the Reports page shows: kind and store are a summary and "These dates" are
   * the page's dates. `edit` makes every field editable.
   */
  mode: 'create' | 'edit'
  stores: StoreOption[]
  /** The business time zone: rolling periods are worked out from today's date there. */
  timeZone: string
  /** The preset preselected when switching to a rolling period. */
  suggestedPreset: RelativePreset
  /** The dates prefilled when switching a rolling period to fixed dates (e.g. its current period). */
  suggestedDates?: DateRange
  submitLabel: string
  busyLabel: string
  /** Rejects with the error to show (e.g. a duplicate name, 409). */
  onSubmit: (input: SavedReportInput) => Promise<void>
  onCancel: () => void
}

type Field = 'name' | 'from' | 'to'

export function SavedReportForm({
  initial,
  mode,
  stores,
  timeZone,
  suggestedPreset,
  suggestedDates,
  submitLabel,
  busyLabel,
  onSubmit,
  onCancel,
}: SavedReportFormProps) {
  const [name, setName] = useState(initial.name)
  const [kind, setKind] = useState<ReportKind>(initial.kind)
  const [storeId, setStoreId] = useState(initial.storeId)
  const [rangeType, setRangeType] = useState(initial.range.type)
  const [from, setFrom] = useState(initial.range.type === 'fixed' ? initial.range.from : (suggestedDates?.from ?? ''))
  const [to, setTo] = useState(initial.range.type === 'fixed' ? initial.range.to : (suggestedDates?.to ?? ''))
  const [preset, setPreset] = useState<RelativePreset>(
    initial.range.type === 'relative' ? initial.range.preset : suggestedPreset,
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const touched = useTouched<Field>()
  const radioName = useId()

  const fixed = rangeType === 'fixed'
  const nameError = name.trim()
    ? name.trim().length > SAVED_REPORT_NAME_MAX
      ? `Use at most ${SAVED_REPORT_NAME_MAX} characters.`
      : null
    : 'Enter a name.'
  const fromError = fixed && !from ? 'Choose a start date.' : null
  const toError = fixed
    ? !to
      ? 'Choose an end date.'
      : from && from > to
        ? 'The end date must be on or after the start date.'
        : from && daysBetweenInclusive(from, to) > MAX_RANGE_DAYS
          ? `The dates may cover at most ${MAX_RANGE_DAYS} days.`
          : null
    : null
  const errors: Record<Field, string | null> = { name: nameError, from: fromError, to: toError }

  // A saved store that no longer appears in the store list (e.g. renamed meanwhile) stays selectable.
  const storeOptions =
    storeId === null || stores.some((s) => s.id === storeId)
      ? stores
      : [...stores, { id: storeId, code: '', name: `Store ${storeId}`, city: null }]
  const storeName = (id: number | null) => (id === null ? 'All stores' : (storeOptions.find((s) => s.id === id)?.name ?? `Store ${id}`))

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    touched.touchAll()
    // In create mode the fixed dates come from the page, so only the name can be wrong there.
    if (nameError || (mode === 'edit' && (fromError || toError))) return
    setBusy(true)
    setError(null)
    try {
      await onSubmit({
        name: name.trim(),
        kind,
        storeId,
        range: fixed ? { type: 'fixed', from, to } : { type: 'relative', preset },
      })
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
    }
  }

  return (
    <form className="form-stack" onSubmit={(e) => void submit(e)} noValidate aria-busy={busy}>
      {error && <FormError>{error}</FormError>}

      <TextField
        label="Name"
        name="saved-report-name"
        autoComplete="off"
        autoFocus
        value={name}
        onChange={setName}
        onBlur={(e) => touched.touch('name', e.currentTarget.value)}
        error={touched.shows('name') ? errors.name : null}
        hint="For example “Q3 by month” or “Boston categories, last 30 days”."
        disabled={busy}
      />

      {mode === 'create' ? (
        <dl className="saved-form-summary">
          <div>
            <dt>Report</dt>
            <dd>{KIND_LABELS[kind]}</dd>
          </div>
          <div>
            <dt>Store</dt>
            <dd className="break-anywhere">{storeName(storeId)}</dd>
          </div>
        </dl>
      ) : (
        <div className="form-columns">
          <SelectField label="Report" value={kind} onChange={(v) => setKind(v as ReportKind)} disabled={busy}>
            {REPORT_TABS.map((tab) => (
              <option key={tab.value} value={tab.value}>
                {KIND_LABELS[tab.value]}
              </option>
            ))}
          </SelectField>
          <SelectField
            label="Store"
            value={storeId === null ? '' : String(storeId)}
            onChange={(v) => setStoreId(v ? Number(v) : null)}
            disabled={busy}
          >
            <option value="">All stores</option>
            {storeOptions.map((store) => (
              <option key={store.id} value={store.id}>
                {store.name}
                {store.city ? ` · ${store.city}` : ''}
              </option>
            ))}
          </SelectField>
        </div>
      )}

      <fieldset className="choice-group" disabled={busy}>
        <legend className="form-label">Dates</legend>

        <label className="choice">
          <input
            type="radio"
            name={radioName}
            checked={fixed}
            onChange={() => setRangeType('fixed')}
          />
          <span>
            <span className="choice-label">{mode === 'create' ? 'These dates' : 'Fixed dates'}</span>
            <span className="choice-hint">
              {mode === 'create'
                ? `${formatDateRange(from, to)}, every time it runs`
                : 'The same period every time it runs'}
            </span>
          </span>
        </label>
        {fixed && mode === 'edit' && (
          <div className="form-columns choice-detail">
            <TextField
              label="From"
              type="date"
              value={from}
              max={to || undefined}
              onChange={setFrom}
              onBlur={(e) => touched.touch('from', e.currentTarget.value)}
              error={touched.shows('from') ? errors.from : null}
            />
            <TextField
              label="To"
              type="date"
              value={to}
              min={from || undefined}
              onChange={setTo}
              onBlur={(e) => touched.touch('to', e.currentTarget.value)}
              error={touched.shows('to') ? errors.to : null}
            />
          </div>
        )}

        <label className="choice">
          <input
            type="radio"
            name={radioName}
            checked={!fixed}
            onChange={() => setRangeType('relative')}
          />
          <span>
            <span className="choice-label">A rolling period</span>
            <span className="choice-hint">Recalculated from today’s date in {timeZone} every time it runs</span>
          </span>
        </label>
        {!fixed && (
          <div className="choice-detail">
            <SelectField label="Period" value={preset} onChange={(v) => setPreset(v as RelativePreset)}>
              {RELATIVE_PRESETS.map((p) => (
                <option key={p.value} value={p.value}>
                  {p.label}
                </option>
              ))}
            </SelectField>
          </div>
        )}
      </fieldset>

      <div className="form-actions dialog-actions">
        <button type="button" className="button button-secondary" onClick={onCancel} disabled={busy}>
          Cancel
        </button>
        <SubmitButton busy={busy} busyLabel={busyLabel}>
          {submitLabel}
        </SubmitButton>
      </div>
    </form>
  )
}
