import { useEffect, useRef, useState, type MouseEvent } from 'react'
import { downloadFile } from '../../api/client'

interface ExportCsvLinkProps {
  href: string
  /** Accessible description of what is downloaded, e.g. "Monthly report". */
  label: string
}

/**
 * Downloads the report CSV. A plain link can't send the selected business (X-Business-Id), so a
 * normal click fetches the file and saves it; the href remains for "open in new tab" and the like.
 */
export function ExportCsvLink({ href, label }: ExportCsvLinkProps) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const controller = useRef<AbortController | null>(null)
  useEffect(() => () => controller.current?.abort(), [])

  const onClick = async (event: MouseEvent<HTMLAnchorElement>) => {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
    event.preventDefault()
    if (busy) return
    controller.current?.abort()
    const current = new AbortController()
    controller.current = current
    setBusy(true)
    setError(null)
    try {
      await downloadFile(href, `${label.replaceAll(' ', '-')}.csv`, current.signal)
    } catch (err) {
      if (!current.signal.aborted) setError(err instanceof Error ? err.message : String(err))
    } finally {
      if (controller.current === current) setBusy(false)
    }
  }

  return (
    <span className="reports-export-wrap">
      <a
        className="button button-secondary reports-export"
        href={href}
        download
        aria-label={`Export ${label} as CSV`}
        aria-busy={busy}
        onClick={(e) => void onClick(e)}
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
        {busy ? 'Exporting…' : 'Export CSV'}
      </a>
      {error && (
        <span className="reports-export-error" role="alert">
          {error}
        </span>
      )}
    </span>
  )
}
