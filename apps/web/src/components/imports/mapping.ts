import type { ImportField, ImportMapping, ImportPreview } from '../../api/imports'

/**
 * Problems with a mapping the browser can see before sending it: a required field without a
 * column (reported only once the user tried to validate) and a column feeding two fields. The API
 * checks the same and more.
 */
export function mappingProblems(fields: ImportField[], mapping: ImportMapping, attempted: boolean): Record<string, string> {
  const problems: Record<string, string> = {}
  const usedBy = new Map<string, ImportField>()
  for (const field of fields) {
    const column = mapping[field.name] ?? null
    if (column === null) {
      if (field.required && attempted) problems[field.name] = `Choose the column that holds the ${field.label.toLowerCase()}.`
      continue
    }
    const earlier = usedBy.get(column)
    if (earlier) problems[field.name] = `“${column}” is already used for ${earlier.label}. A column can feed only one field.`
    else usedBy.set(column, field)
  }
  return problems
}

/** The mapping with every field present (null for unmapped), as the API expects it. */
export function completeMapping(fields: ImportField[], mapping: ImportMapping): ImportMapping {
  return Object.fromEntries(fields.map((f) => [f.name, mapping[f.name] ?? null]))
}

/** The suggested mapping, keeping only columns that exist in the file. */
export function initialMapping(preview: ImportPreview): ImportMapping {
  return Object.fromEntries(
    preview.fields.map((f) => {
      const suggested = preview.suggestedMapping[f.name] ?? null
      return [f.name, suggested !== null && preview.columns.includes(suggested) ? suggested : null]
    }),
  )
}

export const mappingSelectName = (field: string) => `mapping-${field}`
