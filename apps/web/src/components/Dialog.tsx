import { useEffect, useId, useRef, type ReactNode } from 'react'
import { CloseIcon } from './Icons'

interface DialogProps {
  open: boolean
  title: string
  onClose: () => void
  /** While true (e.g. saving), Escape and the close button do nothing. */
  busy?: boolean
  children: ReactNode
}

/**
 * A modal built on the native <dialog>: the browser traps focus, makes the page behind inert and
 * returns focus to the opener on close. The content mounts only while open, so forms start fresh.
 */
export function Dialog({ open, title, onClose, busy = false, children }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    else if (!open && dialog.open) dialog.close()
  }, [open])

  return (
    <dialog
      ref={ref}
      className="dialog"
      aria-labelledby={titleId}
      onCancel={(event) => {
        // Escape: let React state decide, so the dialog never closes behind its owner's back.
        event.preventDefault()
        if (!busy) onClose()
      }}
    >
      {open && (
        <>
          <header className="dialog-header">
            <h2 className="dialog-title" id={titleId}>
              {title}
            </h2>
            <button type="button" className="icon-button dialog-close" onClick={onClose} disabled={busy} aria-label="Close">
              <CloseIcon />
            </button>
          </header>
          <div className="dialog-body">{children}</div>
        </>
      )}
    </dialog>
  )
}
