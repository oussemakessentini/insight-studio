interface ExportCsvLinkProps {
  href: string
  /** Accessible description of what is downloaded, e.g. "Monthly report". */
  label: string
}

/** A plain download link: the API sends the CSV as an attachment with a dated filename. */
export function ExportCsvLink({ href, label }: ExportCsvLinkProps) {
  return (
    <a className="button button-secondary reports-export" href={href} download aria-label={`Export ${label} as CSV`}>
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
      Export CSV
    </a>
  )
}
