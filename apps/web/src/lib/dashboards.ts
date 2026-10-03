import type { BusinessAccess } from '../api/types'

// ---- Permissions -------------------------------------------------------------------------------

export interface DashboardPermissions {
  /** Dashboards belong to members: not in the anonymous public demo (the API answers 401). */
  available: boolean
  /** Create, edit, rename, duplicate and delete: verified OWNER or ADMIN (`require(Role.ADMIN)`). */
  canManage: boolean
  /** An OWNER or ADMIN who could manage dashboards once their email address is verified. */
  needsVerification: boolean
}

/** What the current user may do with dashboards. The API enforces the same rules (contract §4). */
export function dashboardPermissions(access: BusinessAccess): DashboardPermissions {
  const admin = access.role === 'OWNER' || access.role === 'ADMIN'
  return {
    available: access.role !== 'DEMO',
    canManage: admin && access.emailVerified && !access.readOnly,
    needsVerification: admin && !access.emailVerified,
  }
}

// ---- Names -------------------------------------------------------------------------------------

export const DASHBOARD_NAME_MAX = 120
export const MAX_DASHBOARDS = 50

// eslint-disable-next-line no-control-regex
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f-\u009f]/

/** Mirrors the server: 1–120 characters once trimmed, no control characters (contract §1). */
export function dashboardNameError(name: string): string | null {
  const value = name.trim()
  if (!value) return 'Enter a name.'
  if (value.length > DASHBOARD_NAME_MAX) return `Use at most ${DASHBOARD_NAME_MAX} characters.`
  if (CONTROL_CHARACTERS.test(value)) return 'Remove line breaks and other invisible characters.'
  return null
}

/** "3 charts", "1 chart" */
export function widgetCountLabel(count: number): string {
  return `${count} ${count === 1 ? 'chart' : 'charts'}`
}

/** `?revision=n` opens an older revision read-only; anything else is the current one. */
export function revisionParam(search: string): number | null {
  const value = new URLSearchParams(search).get('revision')
  return value && /^\d+$/.test(value) && Number(value) > 0 ? Number(value) : null
}
