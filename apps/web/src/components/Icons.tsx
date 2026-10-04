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

/** Custom dashboards: panels of different sizes on a grid. */
export const LayoutIcon = (p: IconProps) => (
  <Icon {...p}>
    <rect x="3" y="3" width="18" height="18" rx="2" />
    <path d="M3 10h18M12 10v11" />
  </Icon>
)

/** A drag handle: two columns of dots. */
export const GripIcon = (p: IconProps) => (
  <Icon {...p}>
    <circle cx="9" cy="6" r="1" fill="currentColor" />
    <circle cx="15" cy="6" r="1" fill="currentColor" />
    <circle cx="9" cy="12" r="1" fill="currentColor" />
    <circle cx="15" cy="12" r="1" fill="currentColor" />
    <circle cx="9" cy="18" r="1" fill="currentColor" />
    <circle cx="15" cy="18" r="1" fill="currentColor" />
  </Icon>
)

/** A resize corner: two diagonal strokes. */
export const ResizeIcon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M20 12l-8 8M20 18l-2 2" />
  </Icon>
)

export const RefreshIcon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M20 11a8 8 0 0 0-14.8-4.2L4 8.5" />
    <path d="M4 4v4.5h4.5" />
    <path d="M4 13a8 8 0 0 0 14.8 4.2l1.2-1.7" />
    <path d="M20 20v-4.5h-4.5" />
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

export const ChartIcon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M3 3v18h18" />
    <path d="M7 15l4-5 3 3 5-6" />
  </Icon>
)

export const UploadIcon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M12 16V4M7 9l5-5 5 5" />
    <path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" />
  </Icon>
)

export const MenuIcon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M4 6h16M4 12h16M4 18h16" />
  </Icon>
)

export const CheckCircleIcon = (p: IconProps) => (
  <Icon {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M8 12.5l2.5 2.5L16 9.5" />
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

export const ArrowLeftIcon = (p: IconProps) => (
  <Icon width="14" height="14" strokeWidth="2.2" {...p}>
    <path d="M19 12H5M11 6l-6 6 6 6" />
  </Icon>
)

export const ArrowRightIcon = (p: IconProps) => (
  <Icon width="14" height="14" strokeWidth="2.2" {...p}>
    <path d="M5 12h14M13 6l6 6-6 6" />
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

export const UsersIcon = (p: IconProps) => (
  <Icon {...p}>
    <circle cx="9" cy="8" r="3.2" />
    <path d="M3.5 19.5c.6-3 2.9-4.8 5.5-4.8s4.9 1.8 5.5 4.8" />
    <path d="M15.5 5.2a3 3 0 0 1 0 5.6M17.2 14.9c1.8.6 3 2.2 3.3 4.6" />
  </Icon>
)

export const BoxIcon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M12 3 20 7.5v9L12 21l-8-4.5v-9z" />
    <path d="m4 7.5 8 4.5 8-4.5M12 12v9" />
  </Icon>
)

export const UserIcon = (p: IconProps) => (
  <Icon {...p}>
    <circle cx="12" cy="8" r="3.6" />
    <path d="M5 20c.8-3.6 3.6-5.6 7-5.6s6.2 2 7 5.6" />
  </Icon>
)

export const PlusIcon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M12 5v14M5 12h14" />
  </Icon>
)

export const MailIcon = (p: IconProps) => (
  <Icon {...p}>
    <rect x="3.5" y="5.5" width="17" height="13" rx="2" />
    <path d="m4 7 8 6 8-6" />
  </Icon>
)

export const LockIcon = (p: IconProps) => (
  <Icon {...p}>
    <rect x="5" y="10.5" width="14" height="10" rx="2" />
    <path d="M8.5 10.5V7.5a3.5 3.5 0 0 1 7 0v3" />
  </Icon>
)

/** Business settings: a building. */
export const BuildingIcon = (p: IconProps) => (
  <Icon {...p}>
    <rect x="4" y="3" width="16" height="18" rx="1.5" />
    <path d="M9 21v-4h6v4M8 7h2M14 7h2M8 11h2M14 11h2" />
  </Icon>
)

/** Activity history: a clock with a rewind arrow. */
export const HistoryIcon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M3 12a9 9 0 1 0 2.6-6.4L3 8" />
    <path d="M3 3v5h5M12 7v5l3 2" />
  </Icon>
)

/** Downloads: an arrow into a tray. */
export const DownloadIcon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M12 4v11M7 10l5 5 5-5M5 20h14" />
  </Icon>
)

/** Billing: a payment card. */
export const CardIcon = (p: IconProps) => (
  <Icon {...p}>
    <rect x="3" y="5" width="18" height="14" rx="2" />
    <path d="M3 10h18M7 15h4" />
  </Icon>
)
