// Business settings, audit history, exports and deletion (docs/account-management-contract.md).
import { deleteJson, downloadFile, getJson, patchJson } from './client'
import type { Business } from './account'
import type { Role } from './types'

// ---- Business settings (§1) ---------------------------------------------------------------------

export interface BusinessSettings {
  id: number
  name: string
  slug: string
  currency: string
  timeZone: string
  role: Role
  /** False once the business holds products or sales: amounts are never relabelled. */
  currencyChangeAllowed: boolean
  currencyLockedReason: string | null
  createdAt: string
}

export interface BusinessSettingsChange {
  name?: string
  timeZone?: string
  currency?: string
}

/** One month whose totals differ once days are counted in the new zone. Amounts: two-decimal strings. */
export interface TimeZonePreviewMonth {
  month: string
  revenueBefore: string
  revenueAfter: string
  ordersBefore: number
  ordersAfter: number
}

export interface TimeZonePreview {
  from: string
  to: string
  salesTotal: number
  salesChangingDay: number
  salesChangingMonth: number
  /** Only months whose totals differ, newest first, at most 24. */
  months: TimeZonePreviewMonth[]
}

// ---- Audit history (§2) -------------------------------------------------------------------------

export type AuditCategory = 'business' | 'member' | 'import' | 'chart' | 'dashboard'

export interface AuditEvent {
  id: number
  action: string
  /** Null when no account is attached; a deleted account reads `{id, name: "Deleted account"}`. */
  actor: { id: number; name: string } | null
  targetType: string
  targetId: number | null
  details: Record<string, unknown>
  createdAt: string
}

export interface AuditPage {
  events: AuditEvent[]
  /** Pass as `before` for the next (older) page; null when there is none. */
  nextBefore: number | null
}

// ---- Deletion (§4) ------------------------------------------------------------------------------

export interface BusinessDeletionPreview {
  business: { id: number; name: string }
  counts: {
    members: number
    pendingInvitations: number
    stores: number
    products: number
    sales: number
    imports: number
    savedReports: number
    charts: number
    dashboards: number
    auditEvents: number
  }
  otherMembers: { userId: number; displayName: string; role: Role }[]
}

export interface AccountDeletionPreview {
  account: { email: string; displayName: string }
  memberships: { businessId: number; businessName: string; role: Role; memberCount: number }[]
  /** Businesses where this account is the only owner: deletion is refused while any is listed. */
  blockingBusinesses: { businessId: number; businessName: string }[]
  authoredContent: { charts: number; dashboards: number; savedReports: number; imports: number }
}

// Path ids are checked against the business resolved from X-Business-Id, so each call selects the
// business it names. Account calls send no business.
const business = (businessId: number) => `/api/businesses/${businessId}`
const account = { businessId: null }

export const businessSettingsApi = {
  get: (businessId: number, signal?: AbortSignal) =>
    getJson<BusinessSettings>(`${business(businessId)}/settings`, {}, signal, { businessId }),

  update: (businessId: number, change: BusinessSettingsChange) =>
    patchJson<Business>(business(businessId), change, { businessId }),

  timeZonePreview: (businessId: number, timeZone: string, signal?: AbortSignal) =>
    getJson<TimeZonePreview>(`${business(businessId)}/time-zone-preview`, { timeZone }, signal, { businessId }),

  /** Downloads the ZIP of everything the business holds (OWNER; 5 per hour, then 429). */
  exportData: (businessId: number, slug: string) =>
    downloadFile(`${business(businessId)}/export`, `insight-studio-${slug}.zip`, undefined, { businessId }),

  deletionPreview: (businessId: number, signal?: AbortSignal) =>
    getJson<BusinessDeletionPreview>(`${business(businessId)}/deletion-preview`, {}, signal, { businessId }),

  // A wrong password answers 400 "Your password is incorrect."; nothing is deleted then.
  delete: (businessId: number, password: string, confirmName: string) =>
    deleteJson(business(businessId), { businessId, body: { password, confirmName } }),
}

export const auditApi = {
  list: (
    businessId: number,
    query: { category: AuditCategory | null; before?: number | null; limit?: number },
    signal?: AbortSignal,
  ) =>
    getJson<AuditPage>(
      `${business(businessId)}/audit`,
      { limit: query.limit ?? 50, before: query.before, category: query.category },
      signal,
      { businessId },
    ),
}

export const accountDataApi = {
  /** Downloads the account's JSON export (5 per hour, then 429). */
  exportData: () => downloadFile('/api/account/export', 'insight-studio-account.json', undefined, account),

  deletionPreview: (signal?: AbortSignal) =>
    getJson<AccountDeletionPreview>('/api/account/deletion-preview', {}, signal, account),

  delete: (password: string, confirmEmail: string) =>
    deleteJson('/api/account', { ...account, body: { password, confirmEmail } }),
}
