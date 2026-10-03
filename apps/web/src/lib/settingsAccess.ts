import type { BusinessAccess } from '../api/types'

/**
 * Settings › Business and Settings › Activity are for owners and admins (contract §5), verified or
 * not: reading is allowed before verification; changing needs a verified email. Viewers and the
 * demo get the no-access page. The API enforces the same rules.
 */
export function isAdminOrOwner(access: BusinessAccess): boolean {
  return access.role === 'OWNER' || access.role === 'ADMIN'
}

/** Only verified owners change settings, export or delete the business. */
export function canManageBusiness(access: BusinessAccess): boolean {
  return access.role === 'OWNER' && access.emailVerified
}
