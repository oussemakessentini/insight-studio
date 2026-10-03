import { useEffect, useState } from 'react'

/** A callback ref and the content height of the element it is attached to, kept current while it resizes. */
export function useElementHeight<T extends HTMLElement>(): [(element: T | null) => void, number | undefined] {
  const [element, setElement] = useState<T | null>(null)
  const [height, setHeight] = useState<number>()

  useEffect(() => {
    if (!element) return
    const observer = new ResizeObserver(([entry]) => setHeight(Math.floor(entry.contentRect.height)))
    observer.observe(element)
    return () => observer.disconnect()
  }, [element])

  return [setElement, height]
}
