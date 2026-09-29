import type { ReactNode } from 'react'

interface PageHeaderProps {
  eyebrow: ReactNode
  title: ReactNode
  subtitle?: ReactNode
  /** Right-hand side, typically the filter bar. */
  children?: ReactNode
}

export function PageHeader({ eyebrow, title, subtitle, children }: PageHeaderProps) {
  return (
    <header className="page-header">
      <div>
        <div className="page-eyebrow">{eyebrow}</div>
        <h1 className="page-title">{title}</h1>
        {subtitle && <p className="page-subtitle">{subtitle}</p>}
      </div>
      {children}
    </header>
  )
}
