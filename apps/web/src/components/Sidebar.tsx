import type { ComponentType, SVGProps } from 'react'
import { CloseIcon, DashboardIcon, LogoMark, ReceiptIcon, ReportIcon, StoreIcon, TagIcon, UploadIcon } from './Icons'
import { Link } from './Link'

export type Section = 'dashboard' | 'products' | 'sales' | 'stores' | 'reports' | 'imports'

interface NavItem {
  label: string
  icon: ComponentType<SVGProps<SVGSVGElement>>
  section: Section
  /** Shown only when the API reports imports as enabled (local development). */
  requiresImports?: boolean
}

const NAV: NavItem[] = [
  { label: 'Dashboard', icon: DashboardIcon, section: 'dashboard' },
  { label: 'Products', icon: TagIcon, section: 'products' },
  { label: 'Sales', icon: ReceiptIcon, section: 'sales' },
  { label: 'Stores', icon: StoreIcon, section: 'stores' },
  { label: 'Reports', icon: ReportIcon, section: 'reports' },
  { label: 'Import', icon: UploadIcon, section: 'imports', requiresImports: true },
]

interface SidebarProps {
  businessName?: string
  active: Section | null
  hrefs: Record<Section, string>
  importsEnabled: boolean
  open: boolean
  onClose: () => void
}

export function Sidebar({ businessName, active, hrefs, importsEnabled, open, onClose }: SidebarProps) {
  const items = NAV.filter((item) => !item.requiresImports || importsEnabled)
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
            {items.map(({ label, icon: NavIcon, section }) => (
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
