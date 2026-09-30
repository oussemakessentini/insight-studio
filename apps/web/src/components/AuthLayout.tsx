import type { ReactNode } from 'react'
import '../styles/accounts.css'
import { LogoMark } from './Icons'
import { Link } from './Link'

interface AuthLayoutProps {
  title: string
  subtitle?: ReactNode
  /** Links under the card, e.g. "Create an account". */
  footer?: ReactNode
  /** Top-right links, e.g. Account and Sign out during onboarding. */
  headerActions?: ReactNode
  wide?: boolean
  children: ReactNode
}

/** A centered card on the canvas, for signing in and pages outside a business. */
export function AuthLayout({ title, subtitle, footer, headerActions, wide, children }: AuthLayoutProps) {
  return (
    <div className="auth-page">
      <header className="auth-header">
        <Link className="auth-brand" href="/">
          <LogoMark width={26} height={26} />
          <span>Insight Studio</span>
        </Link>
        {headerActions && <div className="auth-header-actions">{headerActions}</div>}
      </header>
      <main className={`auth-card ${wide ? 'auth-card-wide' : ''}`}>
        <div className="auth-card-head">
          <h1 className="auth-title">{title}</h1>
          {subtitle && <p className="auth-subtitle">{subtitle}</p>}
        </div>
        {children}
      </main>
      {footer && <footer className="auth-footer">{footer}</footer>}
    </div>
  )
}
