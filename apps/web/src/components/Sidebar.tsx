import type { ComponentType, SVGProps } from 'react'
import { CloseIcon, DashboardIcon, LogoMark, ReceiptIcon, ReportIcon, StoreIcon, TagIcon } from './Icons'
import { Link } from './Link'

export type Section = 'dashboard' | 'products'

interface NavItem {
  label: string
  icon: ComponentType<SVGProps<SVGSVGElement>>
  /** Pages that exist; the rest signal the planned product shape. */
  section?: Section
}

const NAV: NavItem[] = [
  { label: 'Dashboard', icon: DashboardIcon, section: 'dashboard' },
  { label: 'Products', icon: TagIcon, section: 'products' },
  { label: 'Sales', icon: ReceiptIcon },
  { label: 'Stores', icon: StoreIcon },
  { label: 'Reports', icon: ReportIcon },
]

interface SidebarProps {
  businessName?: string
  active: Section | null
  hrefs: Record<Section, string>
  open: boolean
  onClose: () => void
}

export function Sidebar({ businessName, active, hrefs, open, onClose }: SidebarProps) {
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
            {NAV.map(({ label, icon: NavIcon, section }) => (
              <li key={label}>
                {section ? (
                  <Link
                    className={`nav-item ${active === section ? 'is-active' : ''}`}
                    href={hrefs[section]}
                    aria-current={active === section ? 'page' : undefined}
                    onClick={onClose}
                  >
                    <NavIcon />
                    {label}
                  </Link>
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
