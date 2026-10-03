import { useId, type ComponentType, type SVGProps } from 'react'
import { ROLE_LABELS } from '../api/account'
import type { BusinessAccess } from '../api/types'
import { signInHref } from '../lib/router'
import { currentMembership, useSession } from '../lib/session'
import {
  BoxIcon,
  ChartIcon,
  CloseIcon,
  DashboardIcon,
  LayoutIcon,
  LogoMark,
  PlusIcon,
  ReceiptIcon,
  ReportIcon,
  StoreIcon,
  TagIcon,
  UploadIcon,
  UserIcon,
  UsersIcon,
} from './Icons'
import { Link } from './Link'

export type Section =
  | 'dashboard'
  | 'dashboards'
  | 'products'
  | 'sales'
  | 'stores'
  | 'reports'
  | 'charts'
  | 'imports'
  | 'members'
  | 'catalog'
  | 'account'
  | 'newBusiness'

interface NavItem {
  label: string
  icon: ComponentType<SVGProps<SVGSVGElement>>
  section: Section
  /** Shown only when the current access allows it (the API enforces it regardless). */
  allowed?: (access: BusinessAccess) => boolean
}

const NAV: NavItem[] = [
  { label: 'Overview', icon: DashboardIcon, section: 'dashboard' },
  { label: 'Dashboards', icon: LayoutIcon, section: 'dashboards' },
  { label: 'Products', icon: TagIcon, section: 'products' },
  { label: 'Sales', icon: ReceiptIcon, section: 'sales' },
  { label: 'Stores', icon: StoreIcon, section: 'stores' },
  { label: 'Reports', icon: ReportIcon, section: 'reports' },
  { label: 'Charts', icon: ChartIcon, section: 'charts' },
  { label: 'Import', icon: UploadIcon, section: 'imports', allowed: (a) => a.canImport },
]

const SETTINGS_NAV: NavItem[] = [
  { label: 'Members', icon: UsersIcon, section: 'members', allowed: (a) => a.canManageMembers },
  { label: 'Catalog', icon: BoxIcon, section: 'catalog', allowed: (a) => a.canManageCatalog },
]

interface SidebarProps {
  businessName?: string
  /** Null while the business context is loading: only the always-available pages are listed. */
  access: BusinessAccess | null
  active: Section | null
  hrefs: Record<Section, string>
  open: boolean
  onClose: () => void
}

export function Sidebar({ businessName, access, active, hrefs, open, onClose }: SidebarProps) {
  const visible = (item: NavItem) => !item.allowed || (access !== null && item.allowed(access))
  const settings = SETTINGS_NAV.filter(visible)

  const navLink = ({ label, icon: NavIcon, section }: NavItem) => (
    <li key={label}>
      <Link
        className={`nav-item ${active === section ? 'is-active' : ''}`}
        href={hrefs[section]}
        aria-current={active === section ? 'page' : undefined}
        onClick={onClose}
      >
        <NavIcon />
        {label}
      </Link>
    </li>
  )

  return (
    <>
      <aside id="sidebar" className={`sidebar ${open ? 'is-open' : ''}`} aria-label="Main navigation">
        <div className="sidebar-brand">
          <LogoMark />
          <span>Insight Studio</span>
          <button type="button" className="icon-button sidebar-close" onClick={onClose} aria-label="Close menu">
            <CloseIcon />
          </button>
        </div>

        <BusinessSwitcher businessName={businessName} access={access} onNavigate={onClose} newBusinessHref={hrefs.newBusiness} />

        <nav className="sidebar-nav" aria-label="Pages">
          <ul className="nav-list">{NAV.filter(visible).map(navLink)}</ul>
          {settings.length > 0 && (
            <>
              <p className="nav-heading">Settings</p>
              <ul className="nav-list">{settings.map(navLink)}</ul>
            </>
          )}
        </nav>

        <SidebarFooter accountHref={hrefs.account} accountActive={active === 'account'} onNavigate={onClose} />
      </aside>
      {open && <div className="sidebar-backdrop" onClick={onClose} aria-hidden="true" />}
    </>
  )
}

/** The current business with the user's role; a menu of their memberships when there are several. */
function BusinessSwitcher({
  businessName,
  access,
  newBusinessHref,
  onNavigate,
}: {
  businessName?: string
  access: BusinessAccess | null
  newBusinessHref: string
  onNavigate: () => void
}) {
  const value = useSession()
  const selectId = useId()
  if (!value) return null
  const { session, selectBusiness } = value

  if (!session.authenticated) {
    if (!session.demo?.enabled) return null
    return (
      <div className="sidebar-business">
        <span className="sidebar-business-label">Demo business</span>
        <span className="sidebar-business-name">{businessName ?? session.demo.name}</span>
        <div className="sidebar-business-meta">
          <span className="sidebar-role">Read-only</span>
        </div>
      </div>
    )
  }

  const membership = currentMembership(value)
  if (!membership) return null
  const role = access?.role ?? membership.role

  return (
    <div className="sidebar-business">
      {session.memberships.length > 1 ? (
        <>
          <label className="sidebar-business-label" htmlFor={selectId}>
            Business
          </label>
          <select
            id={selectId}
            className="sidebar-select"
            value={membership.businessId}
            onChange={(e) => {
              onNavigate()
              selectBusiness(Number(e.target.value))
            }}
          >
            {session.memberships.map((m) => (
              <option key={m.businessId} value={m.businessId}>
                {m.name} · {ROLE_LABELS[m.role]}
              </option>
            ))}
          </select>
        </>
      ) : (
        <>
          <span className="sidebar-business-label">Business</span>
          <span className="sidebar-business-name">{businessName ?? membership.name}</span>
        </>
      )}
      <div className="sidebar-business-meta">
        <span className="sidebar-role" title="Your role in this business">
          {ROLE_LABELS[role]}
        </span>
        <Link className="sidebar-new-business" href={newBusinessHref} onClick={onNavigate}>
          <PlusIcon width={14} height={14} />
          New business
        </Link>
      </div>
    </div>
  )
}

function SidebarFooter({
  accountHref,
  accountActive,
  onNavigate,
}: {
  accountHref: string
  accountActive: boolean
  onNavigate: () => void
}) {
  const value = useSession()
  if (!value) return null
  const { session } = value

  if (!session.authenticated || !session.user) {
    if (!session.demo?.enabled) return null
    return (
      <div className="sidebar-footer sidebar-footer-actions">
        <Link className="button button-primary sidebar-footer-button" href={signInHref()} onClick={onNavigate}>
          Sign in
        </Link>
        <Link className="button sidebar-footer-button sidebar-button-ghost" href={signInHref(undefined, '/sign-up')} onClick={onNavigate}>
          Create account
        </Link>
      </div>
    )
  }

  return (
    <div className="sidebar-footer">
      <Link
        className={`sidebar-account ${accountActive ? 'is-active' : ''}`}
        href={accountHref}
        aria-current={accountActive ? 'page' : undefined}
        onClick={onNavigate}
      >
        <UserIcon />
        <span className="sidebar-account-text">
          <span className="sidebar-footer-name">{session.user.displayName}</span>
          <span className="sidebar-account-email">{session.user.email}</span>
        </span>
      </Link>
    </div>
  )
}
