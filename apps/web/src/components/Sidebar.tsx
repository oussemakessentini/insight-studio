import type { ComponentType, SVGProps } from 'react'
import { CloseIcon, DashboardIcon, LogoMark, ReceiptIcon, ReportIcon, StoreIcon, TagIcon } from './Icons'

interface NavItem {
  label: string
  icon: ComponentType<SVGProps<SVGSVGElement>>
  active?: boolean
}

// Only the dashboard exists in this version; the rest signal the planned product shape.
const NAV: NavItem[] = [
  { label: 'Dashboard', icon: DashboardIcon, active: true },
  { label: 'Sales', icon: ReceiptIcon },
  { label: 'Products', icon: TagIcon },
  { label: 'Stores', icon: StoreIcon },
  { label: 'Reports', icon: ReportIcon },
]

interface SidebarProps {
  businessName?: string
  open: boolean
  onClose: () => void
}

export function Sidebar({ businessName, open, onClose }: SidebarProps) {
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

        <nav>
          <ul className="nav-list">
            {NAV.map(({ label, icon: NavIcon, active }) => (
              <li key={label}>
                {active ? (
                  <a className="nav-item is-active" href="/" aria-current="page">
                    <NavIcon />
                    {label}
                  </a>
                ) : (
                  <span className="nav-item is-disabled" aria-disabled="true" title="Coming soon">
                    <NavIcon />
                    {label}
                    <span className="nav-badge">Soon</span>
                  </span>
                )}
              </li>
            ))}
          </ul>
        </nav>

        {businessName && (
          <div className="sidebar-footer">
            <span className="sidebar-footer-label">Workspace</span>
            <span className="sidebar-footer-name">{businessName}</span>
          </div>
        )}
      </aside>
      {open && <div className="sidebar-backdrop" onClick={onClose} aria-hidden="true" />}
    </>
  )
}
