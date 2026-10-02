import type { ImportMapping, ImportPreview } from '../../api/imports'
import { formatNumber } from '../../lib/format'
import { SelectField } from '../Form'
import { mappingSelectName } from './mapping'

interface MappingFieldsProps {
  preview: ImportPreview
  mapping: ImportMapping
  onChange: (field: string, column: string | null) => void
  /** Field name → message, from the browser's checks or the API's file-level errors. */
  errors: Record<string, string>
  disabled: boolean
}

/** One labelled select per field, listing the file's columns. */
export function MappingFields({ preview, mapping, onChange, errors, disabled }: MappingFieldsProps) {
  return (
    <fieldset className="imports-mapping" disabled={disabled}>
      <legend className="imports-subheading">Match each field to a column of your file</legend>
      <p className="imports-hint">
        Fields marked <span className="imports-required">required</span> need a column. Other columns of the file are
        ignored.
      </p>
      <div className="imports-mapping-grid">
        {preview.fields.map((field) => (
          <SelectField
            key={field.name}
            name={mappingSelectName(field.name)}
            label={field.required ? `${field.label} (required)` : field.label}
            value={mapping[field.name] ?? ''}
            onChange={(value) => onChange(field.name, value === '' ? null : value)}
            error={errors[field.name] ?? null}
            hint={field.description}
            fieldClassName="imports-mapping-field"
            required={field.required}
          >
            <option value="">{field.required ? 'Choose a column…' : 'Not mapped'}</option>
            {preview.columns.map((column, i) => (
              <option key={i} value={column}>
                {column}
              </option>
            ))}
          </SelectField>
        ))}
      </div>
    </fieldset>
  )
}

/** The file's first rows as read by the API, with the field each column feeds. Scrolls sideways. */
export function PreviewTable({ preview, mapping }: { preview: ImportPreview; mapping: ImportMapping }) {
  const fieldOf = (column: string) => preview.fields.find((f) => mapping[f.name] === column)
  const shown = preview.sampleRows.length
  return (
    <div className="imports-preview">
      <p className="imports-subheading" id="imports-preview-caption">
        First {shown === 1 ? 'row' : `${formatNumber(shown)} rows`} of {formatNumber(preview.rowCount)}
      </p>
      <div className="table-scroll imports-preview-scroll" tabIndex={0} role="region" aria-labelledby="imports-preview-caption">
        <table className="data-table imports-preview-table">
          <thead>
            <tr>
              {preview.columns.map((column, i) => {
                const field = fieldOf(column)
                return (
                  <th key={i} scope="col">
                    <span className="imports-preview-column">{column}</span>
                    {field ? (
                      <span className="imports-preview-field">→ {field.label}</span>
                    ) : (
                      <span className="imports-preview-field is-unused">Not used</span>
                    )}
                  </th>
                )
              })}
            </tr>
          </thead>
          <tbody>
            {preview.sampleRows.map((row, r) => (
              <tr key={r}>
                {preview.columns.map((_, c) => (
                  <td key={c} className="imports-preview-cell" title={row[c] ?? ''}>
                    {row[c] ?? ''}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
