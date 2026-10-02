import { useEffect, useRef, useState } from 'react'

interface DownloadButtonProps {
  /** Fetches and saves the file; rejects with a user-facing message on failure. */
  download: (signal: AbortSignal) => Promise<void>
  label: string
  busyLabel: string
  disabled?: boolean
}

/**
 * A button that saves a file fetched with the business header (templates, errors CSV), showing
 * progress and any failure next to it, like the report exports.
 */
export function DownloadButton({ download, label, busyLabel, disabled = false }: DownloadButtonProps) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const controller = useRef<AbortController | null>(null)
  useEffect(() => () => controller.current?.abort(), [])

  const onClick = async () => {
    if (busy) return
    controller.current?.abort()
    const current = new AbortController()
    controller.current = current
    setBusy(true)
    setError(null)
    try {
      await download(current.signal)
    } catch (err) {
      if (!current.signal.aborted) setError(err instanceof Error ? err.message : String(err))
    } finally {
      if (controller.current === current) setBusy(false)
    }
  }

  return (
    <span className="imports-download">
      <button
        type="button"
        className="button button-secondary imports-download-button"
        disabled={disabled || busy}
        aria-busy={busy}
        onClick={() => void onClick()}
      >
        <svg
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
          focusable="false"
        >
          <path d="M12 4v11" />
          <path d="m7 10 5 5 5-5" />
          <path d="M5 19h14" />
        </svg>
        {busy ? busyLabel : label}
      </button>
      {error && (
        <span className="imports-download-error" role="alert">
          {error}
        </span>
      )}
    </span>
  )
}
