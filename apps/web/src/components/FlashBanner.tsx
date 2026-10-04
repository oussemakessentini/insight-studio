import { useEffect, useRef } from 'react'
import { dismissFlash, useFlash } from '../lib/flash'
import { usePathname } from '../lib/router'
import { CheckCircleIcon, CloseIcon } from './Icons'

/**
 * A confirmation carried over from the previous page (lib/flash). Announced politely; stays until
 * dismissed or until the user goes to another page.
 */
export function FlashBanner() {
  const notice = useFlash()
  const pathname = usePathname()
  const shownOn = useRef<{ id: number; pathname: string } | null>(null)

  useEffect(() => {
    if (!notice) {
      shownOn.current = null
    } else if (!shownOn.current || shownOn.current.id !== notice.id) {
      shownOn.current = { id: notice.id, pathname }
    } else if (shownOn.current.pathname !== pathname) {
      dismissFlash(notice.id)
    }
  }, [notice, pathname])

  if (!notice) return null
  return (
    <div className="demo-banner flash-banner" role="status">
      <CheckCircleIcon width={16} height={16} />
      <p>{notice.message}</p>
      <button type="button" className="icon-button flash-dismiss" onClick={() => dismissFlash(notice.id)} aria-label="Dismiss">
        <CloseIcon width={16} height={16} />
      </button>
    </div>
  )
}
