// Minimal inline icons (stroke-based, 24px grid) so the app needs no icon dependency.
import type { SVGProps } from 'react'

type IconProps = SVGProps<SVGSVGElement>

function Icon({ children, ...props }: IconProps) {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...props}
    >
      {children}
    </svg>
  )
}

export const DashboardIcon = (p: IconProps) => (
  <Icon {...p}>
    <rect x="3" y="3" width="7" height="9" rx="1.5" />
    <rect x="14" y="3" width="7" height="5" rx="1.5" />
    <rect x="14" y="12" width="7" height="9" rx="1.5" />
    <rect x="3" y="16" width="7" height="5" rx="1.5" />
  </Icon>
)

export const ReceiptIcon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M6 3h12v18l-3-2-3 2-3-2-3 2z" />
    <path d="M9 8h6M9 12h6" />
  </Icon>
)

export const TagIcon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M3 12V4a1 1 0 0 1 1-1h8l9 9-9 9z" />
    <circle cx="8" cy="8" r="1.5" />
  </Icon>
)

export const StoreIcon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M4 10v10h16V10" />
    <path d="M3 4h18l-1.5 6h-15z" />
    <path d="M10 20v-5h4v5" />
  </Icon>
)

export const ReportIcon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M4 20V10M10 20V4M16 20v-7M22 20H2" />
  </Icon>
)

export const MenuIcon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M4 6h16M4 12h16M4 18h16" />
  </Icon>
)

export const CloseIcon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M6 6l12 12M18 6L6 18" />
  </Icon>
)

export const ArrowUpIcon = (p: IconProps) => (
  <Icon width="14" height="14" strokeWidth="2.2" {...p}>
    <path d="M12 19V5M6 11l6-6 6 6" />
  </Icon>
)

export const ArrowDownIcon = (p: IconProps) => (
  <Icon width="14" height="14" strokeWidth="2.2" {...p}>
    <path d="M12 5v14M6 13l6 6 6-6" />
  </Icon>
)

export const AlertIcon = (p: IconProps) => (
  <Icon {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7.5v5.5M12 16.5v.01" />
  </Icon>
)

export const LogoMark = (p: IconProps) => (
  <svg width="28" height="28" viewBox="0 0 32 32" aria-hidden="true" focusable="false" {...p}>
    <rect width="32" height="32" rx="8" fill="#2a78d6" />
    <path d="M9 22v-5M14 22V10M19 22v-8M24 22v-11" stroke="#fff" strokeWidth="2.6" strokeLinecap="round" />
  </svg>
)
