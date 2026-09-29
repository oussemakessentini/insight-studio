import type { ReactNode } from 'react'
import { AlertIcon } from './Icons'

interface PanelProps {
  title: string
  subtitle?: string
  actions?: ReactNode
  className?: string
  children: ReactNode
}

export function Panel({ title, subtitle, actions, className, children }: PanelProps) {
  return (
    <section className={`panel ${className ?? ''}`} aria-label={title}>
      <header className="panel-header">
        <div>
          <h2 className="panel-title">{title}</h2>
          {subtitle && <p className="panel-subtitle">{subtitle}</p>}
        </div>
        {actions && <div className="panel-actions">{actions}</div>}
      </header>
      <div className="panel-body">{children}</div>
    </section>
  )
}

interface AsyncContentProps<T> {
  data: T | undefined
  error: Error | undefined
  loading: boolean
  retry: () => void
  isEmpty?: (data: T) => boolean
  emptyMessage?: string
  skeleton: ReactNode
  children: (data: T) => ReactNode
}

/**
 * Chooses between skeleton, error, empty and content. While refetching, the previous content
 * stays visible (dimmed) instead of collapsing to a skeleton.
 */
export function AsyncContent<T>({
  data,
  error,
  loading,
  retry,
  isEmpty,
  emptyMessage = 'No sales in this period.',
  skeleton,
  children,
}: AsyncContentProps<T>) {
  if (error) return <ErrorState message={error.message} onRetry={retry} />
  if (data === undefined) return <>{skeleton}</>
  if (isEmpty?.(data)) return <EmptyState message={emptyMessage} />
  return (
    <div className={loading ? 'is-refreshing' : undefined} aria-busy={loading}>
      {children(data)}
    </div>
  )
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="state state-error" role="alert">
      <AlertIcon />
      <p>{message}</p>
      {onRetry && (
        <button type="button" className="button button-secondary" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  )
}

export function EmptyState({ message }: { message: string }) {
  return (
    <div className="state state-empty">
      <p>{message}</p>
    </div>
  )
}

export function Skeleton({ height = 16, width = '100%' }: { height?: number; width?: number | string }) {
  return <span className="skeleton" style={{ height, width }} aria-hidden="true" />
}

export function SkeletonRows({ rows = 5 }: { rows?: number }) {
  return (
    <div className="skeleton-rows" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} height={18} width={`${92 - (i % 3) * 12}%`} />
      ))}
    </div>
  )
}
