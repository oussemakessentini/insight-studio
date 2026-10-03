import { useEffect, useState } from 'react'

/** A callback ref and the content width of the element it is attached to, kept current while it resizes. */
export function useElementWidth<T extends HTMLElement>(): [(element: T | null) => void, number | undefined] {
  const [element, setElement] = useState<T | null>(null)
  const [width, setWidth] = useState<number>()

  useEffect(() => {
    if (!element) return
    const observer = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)))
    observer.observe(element)
    return () => observer.disconnect()
  }, [element])

  return [setElement, width]
}
