import { useCallback, useEffect, useEffectEvent, useState } from 'react'

export interface ApiState<T> {
  /** Latest successful result. Kept while a newer request loads so panels don't flash empty. */
  data: T | undefined
  error: Error | undefined
  loading: boolean
  retry: () => void
}

interface Settled<T> {
  requestKey: string
  data: T | undefined
  error: Error | undefined
}

/**
 * Runs `load` whenever `key` changes, aborting the previous request.
 * `key` must capture every input `load` depends on.
 */
export function useApi<T>(key: string, load: (signal: AbortSignal) => Promise<T>): ApiState<T> {
  const [attempt, setAttempt] = useState(0)
  const [settled, setSettled] = useState<Settled<T>>()
  const requestKey = `${key}#${attempt}`
  const runLoad = useEffectEvent(load)

  useEffect(() => {
    const controller = new AbortController()
    runLoad(controller.signal)
      .then((data) => setSettled({ requestKey, data, error: undefined }))
      .catch((err: unknown) => {
        if (controller.signal.aborted) return
        const error = err instanceof Error ? err : new Error(String(err))
        setSettled((prev) => ({ requestKey, data: prev?.data, error }))
      })
    return () => controller.abort()
  }, [requestKey])

  const retry = useCallback(() => setAttempt((n) => n + 1), [])
  const loading = settled?.requestKey !== requestKey
  return {
    data: settled?.data,
    error: loading ? undefined : settled?.error,
    loading,
    retry,
  }
}
