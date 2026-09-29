import { useCallback, useState } from 'react'

/**
 * Like `useState`, but the value falls back to `resetValue` whenever `resetKey` changes
 * (e.g. back to page 1 when filters change) without an extra effect or render pass.
 * `firstValue` seeds the very first render, e.g. from the URL.
 */
export function useStateResetOn<T>(resetKey: string, resetValue: T, firstValue: T = resetValue): [T, (value: T) => void] {
  const [state, setState] = useState({ key: resetKey, value: firstValue })
  const value = state.key === resetKey ? state.value : resetValue
  const set = useCallback((next: T) => setState({ key: resetKey, value: next }), [resetKey])
  return [value, set]
}
