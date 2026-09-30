import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes } from 'react'
import { AlertIcon } from './Icons'

interface FieldShellProps {
  label: string
  /** Shown once the field was left or the form submitted; null when valid. */
  error?: string | null
  hint?: ReactNode
  optional?: boolean
  className?: string
  children: (ids: { inputId: string; describedBy: string | undefined; invalid: boolean }) => ReactNode
}

function FieldShell({ label, error, hint, optional, className, children }: FieldShellProps) {
  const inputId = useId()
  const hintId = `${inputId}-hint`
  const errorId = `${inputId}-error`
  const describedBy = [error ? errorId : null, hint ? hintId : null].filter(Boolean).join(' ') || undefined
  return (
    <div className={`form-field ${className ?? ''}`}>
      <label className="form-label" htmlFor={inputId}>
        {label}
        {optional && <span className="form-optional"> (optional)</span>}
      </label>
      {children({ inputId, describedBy, invalid: Boolean(error) })}
      {error && (
        <p className="form-error" id={errorId}>
          {error}
        </p>
      )}
      {hint && !error && (
        <p className="form-hint" id={hintId}>
          {hint}
        </p>
      )}
    </div>
  )
}

type TextFieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'value' | 'id'> & {
  label: string
  value: string
  onChange: (value: string) => void
  error?: string | null
  hint?: ReactNode
  optional?: boolean
  fieldClassName?: string
}

/** A labelled input with inline error and hint, wired for screen readers. */
export function TextField({ label, value, onChange, error, hint, optional, fieldClassName, ...input }: TextFieldProps) {
  return (
    <FieldShell label={label} error={error} hint={hint} optional={optional} className={fieldClassName}>
      {({ inputId, describedBy, invalid }) => (
        <input
          id={inputId}
          className="control form-control"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          aria-invalid={invalid || undefined}
          aria-describedby={describedBy}
          {...input}
        />
      )}
    </FieldShell>
  )
}

type SelectFieldProps = Omit<SelectHTMLAttributes<HTMLSelectElement>, 'onChange' | 'value' | 'id'> & {
  label: string
  value: string
  onChange: (value: string) => void
  hint?: ReactNode
  error?: string | null
  fieldClassName?: string
  children: ReactNode
}

export function SelectField({ label, value, onChange, hint, error, fieldClassName, children, ...select }: SelectFieldProps) {
  return (
    <FieldShell label={label} error={error} hint={hint} className={fieldClassName}>
      {({ inputId, describedBy, invalid }) => (
        <select
          id={inputId}
          className="control form-control"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          aria-invalid={invalid || undefined}
          aria-describedby={describedBy}
          {...select}
        >
          {children}
        </select>
      )}
    </FieldShell>
  )
}

/** A server or request error for a whole form. */
export function FormError({ children }: { children: ReactNode }) {
  return (
    <div className="form-alert form-alert-error" role="alert">
      <AlertIcon />
      <div>{children}</div>
    </div>
  )
}

/** A confirmation that something was saved. */
export function FormSuccess({ children }: { children: ReactNode }) {
  return (
    <div className="form-alert form-alert-success" role="status">
      <div>{children}</div>
    </div>
  )
}

export function SubmitButton({
  busy,
  busyLabel,
  disabled,
  className,
  children,
}: {
  busy: boolean
  busyLabel: string
  disabled?: boolean
  className?: string
  children: ReactNode
}) {
  return (
    <button type="submit" className={`button button-primary ${className ?? ''}`} disabled={busy || disabled} aria-busy={busy}>
      {busy ? busyLabel : children}
    </button>
  )
}
