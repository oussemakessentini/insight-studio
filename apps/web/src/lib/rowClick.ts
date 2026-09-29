import type { MouseEvent } from 'react'
import { navigate } from './router'

/**
 * Makes a whole table row open `href` on click. The row's link stays the accessible target
 * (keyboard, screen readers, open in new tab); this only widens the mouse hit area.
 */
export function rowClick(href: string) {
  return (event: MouseEvent<HTMLTableRowElement>) => {
    if ((event.target as HTMLElement).closest('a, button')) return
    if (window.getSelection()?.toString()) return // let people select text
    navigate(href)
  }
}
