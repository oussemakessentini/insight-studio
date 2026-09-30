import { useCallback, useState } from 'react'

export interface Touched<K extends string> {
  /** Whether to show `field`'s error: after it was left once, or after a submit attempt. */
  shows: (field: K) => boolean
  touch: (field: K) => void
  /** Marks every field (on submit), so all errors show. */
  touchAll: () => void
  reset: () => void
}

/** Inline validation that doesn't shout at a field the user hasn't finished typing in. */
export function useTouched<K extends string>(): Touched<K> {
  const [fields, setFields] = useState<ReadonlySet<K>>(new Set())
  const [all, setAll] = useState(false)
  const touch = useCallback((field: K) => setFields((prev) => (prev.has(field) ? prev : new Set(prev).add(field))), [])
  const touchAll = useCallback(() => setAll(true), [])
  const reset = useCallback(() => {
    setFields(new Set())
    setAll(false)
  }, [])
  return { shows: (field) => all || fields.has(field), touch, touchAll, reset }
}
