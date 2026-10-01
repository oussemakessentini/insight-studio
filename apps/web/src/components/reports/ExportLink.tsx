import { useEffect, useRef, useState, type MouseEvent } from 'react'
import { downloadFile } from '../../api/client'
import type { ExportFormat } from '../../api/reports'

interface ExportLinkProps {
  href: string
  /** Accessible description of what is downloaded, e.g. "monthly report". */
  label: string
  format: ExportFormat
}

/**
 * Downloads a report as CSV or PDF. A plain link can't send the selected business (X-Business-Id),
 * so a normal click fetches the file and saves it; the href remains for "open in new tab" and the like.
 */
export function ExportLink({ href, label, format }: ExportLinkProps) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const controller = useRef<AbortController | null>(null)
  useEffect(() => () => controller.current?.abort(), [])
  const formatName = format.toUpperCase()

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
      await downloadFile(href, `${label.replaceAll(' ', '-')}.${format}`, current.signal)
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
        aria-label={`Export ${label} as ${formatName}`}
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
        {busy ? 'Exporting…' : `Export ${formatName}`}
      </a>
      {error && (
        <span className="reports-export-error" role="alert">
          {error}
        </span>
      )}
    </span>
  )
}

/** The CSV and PDF downloads of one report, side by side. */
export function ExportLinks({ csvHref, pdfHref, label }: { csvHref: string; pdfHref: string; label: string }) {
  return (
    <>
      <ExportLink href={csvHref} label={label} format="csv" />
      <ExportLink href={pdfHref} label={label} format="pdf" />
    </>
  )
}
