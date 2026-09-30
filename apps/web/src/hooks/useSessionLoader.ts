import { useCallback, useEffect, useRef, useState } from 'react'
import { sessionApi, type Session } from '../api/account'
import { navigate } from '../lib/router'

interface SessionState {
  session: Session | undefined
  /** The last load failed (e.g. the API is unreachable); the previous session, if any, stays. */
  error: Error | undefined
  reload: (thenGo?: (session: Session) => string | null) => Promise<Session>
}

/** Loads GET /api/session on start and whenever `reload` is called; only the latest load counts. */
export function useSessionLoader(): SessionState {
  const [state, setState] = useState<{ session?: Session; error?: Error }>({})
  const latest = useRef(0)

  const reload = useCallback(async (thenGo?: (session: Session) => string | null) => {
    const request = ++latest.current
    try {
      const session = await sessionApi.get()
      if (request === latest.current) {
        // Navigate and update the session in the same tick so they render together.
        const to = thenGo?.(session)
        if (to) navigate(to, { replace: true })
        setState({ session })
      }
      return session
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err))
      if (request === latest.current) setState((prev) => ({ session: prev.session, error }))
      throw error
    }
  }, [])

  useEffect(() => {
    reload().catch(() => undefined)
  }, [reload])

  return { session: state.session, error: state.error, reload }
}
