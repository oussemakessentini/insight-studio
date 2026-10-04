import { useSyncExternalStore } from 'react'

/**
 * One-shot confirmations that must survive a navigation inside the app, such as "Doomed Co was
 * deleted" after the app has moved to another business. In memory only: a full page reload drops
 * them, which is fine for a confirmation of something the user just did.
 */
export type Flash = { id: number; message: string }

let current: Flash | null = null
let nextId = 1
const listeners = new Set<() => void>()

function emit(): void {
  for (const listener of listeners) listener()
}

/** Shows `message` on the next page (until dismissed or the user moves on to another page). */
export function flash(message: string): void {
  current = { id: nextId++, message }
  emit()
}

export function dismissFlash(id?: number): void {
  if (current && (id === undefined || current.id === id)) {
    current = null
    emit()
  }
}

export function useFlash(): Flash | null {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => current,
  )
}

/** The confirmation shown after a business deletion moved the user elsewhere. */
export function businessDeletedMessage(deleted: string, now: string | null): string {
  return now ? `“${deleted}” was deleted. You're now working in “${now}”.` : `“${deleted}” was deleted.`
}
