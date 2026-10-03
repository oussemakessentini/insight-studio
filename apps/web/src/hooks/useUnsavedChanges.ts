import { useCallback, useEffect, useState } from 'react'
import { blockNavigation } from '../lib/router'

export interface UnsavedChanges {
  /** A navigation is waiting for the user's answer. */
  asking: boolean
  stay: () => void
  leave: () => void
}

/**
 * While `dirty`, leaving the page asks first: in-app links and back/forward through the router
 * (answered with `stay` / `leave`), refresh and closing the tab through the browser's own prompt.
 */
export function useUnsavedChanges(dirty: boolean): UnsavedChanges {
  const [pending, setPending] = useState<{ proceed: () => void } | null>(null)

  useEffect(() => {
    if (!dirty) return
    const release = blockNavigation((proceed) => setPending({ proceed }))
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      // Older browsers show the prompt only when returnValue is set.
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => {
      release()
      window.removeEventListener('beforeunload', onBeforeUnload)
    }
  }, [dirty])

  const stay = useCallback(() => setPending(null), [])
  const leave = useCallback(() => {
    setPending(null)
    pending?.proceed()
  }, [pending])

  return { asking: pending !== null, stay, leave }
}
