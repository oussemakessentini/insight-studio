import { IMPORT_KINDS, importsApi, type ImportKind } from '../../api/imports'
import { Panel } from '../Panel'
import { DownloadButton } from './DownloadButton'
import { KINDS } from './kinds'

interface ImportTypePickerProps {
  value: ImportKind
  onChange: (kind: ImportKind) => void
  /** While a file is being sent, switching types would abandon it. */
  disabled: boolean
}

/** Step 1: what the file contains, and that type's CSV template. */
export function ImportTypePicker({ value, onChange, disabled }: ImportTypePickerProps) {
  return (
    <Panel title="1. Choose what to import" subtitle="Each type has its own columns; the template shows them with examples.">
      <fieldset className="imports-kinds" disabled={disabled}>
        <legend className="visually-hidden">Import type</legend>
        {IMPORT_KINDS.map((kind) => (
          <label key={kind} className={`imports-kind ${kind === value ? 'is-selected' : ''}`}>
            <input
              type="radio"
              name="import-kind"
              value={kind}
              checked={kind === value}
              onChange={() => onChange(kind)}
            />
            <span className="imports-kind-text">
              <span className="imports-kind-label">{KINDS[kind].label}</span>
              <span className="imports-kind-description">{KINDS[kind].description}</span>
            </span>
          </label>
        ))}
      </fieldset>
      <div className="imports-template">
        <DownloadButton
          download={(signal) => importsApi.downloadTemplate(value, signal)}
          label={`Download ${KINDS[value].label.toLowerCase()} template`}
          busyLabel="Downloading…"
        />
        {value === 'sales' && (
          <p className="imports-hint">Stores and products must exist first: import or add them before their sales.</p>
        )}
      </div>
    </Panel>
  )
}
